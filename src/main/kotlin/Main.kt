import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Timer
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.schedule
import org.json.JSONObject

val client = OkHttpClient()
val logger: Logger = Logger.getLogger("AssessmentLogger")

fun main() {
    // Load the JDBC driver explicitly
    try {
        Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
    } catch (e: ClassNotFoundException) {
        logger.severe("SQLServerDriver not found: ${e.message}")
        return
    }

    // Database connection details
    val jdbcUrl = "jdbc:sqlserver://192.168.100.102:1433;databaseName=APA_PRD"
    val username = "APA_TRN"
    val password = "APA_TRN"

    // Retrieve token
    val token = retrieveToken()

    // Establish database connection
    val connection: Connection
    try {
        connection = DriverManager.getConnection(jdbcUrl, username, password)
    } catch (e: SQLException) {
        logger.severe("Failed to connect to the database: ${e.message}")
        return
    }

    // Schedule the task to run every 10 minutes
    val timer = Timer()
    timer.schedule(0L, 10 * 60 * 1000) {
        processAssessments(connection, token)
    }
}

fun retrieveToken(): String {
    val client = OkHttpClient()
    val mediaType = "application/json".toMediaType()
    val json = """
        {
            "username": "gAAAAABfqhmdiVzoGThMkYkX1wKTlbK_yh1XLdECahns85T9XhNl7Lff3I-frOyn8gGoWSctvVTw-4woa8gkRly9RQPZz6n67w==",
            "password": "gAAAAABfqhmdiVzoGThMkYkX1wKTlbK_yh1XLdECahns85T9XhNl7Lff3I-frOyn8gGoWSctvVTw-4woa8gkRly9RQPZz6n67w=="
        }
    """.trimIndent()
    val body = json.toRequestBody(mediaType)

    val request = Request.Builder()
        .url("http://192.168.100.79/iail/auth")
        .post(body)
        .addHeader("Content-Type", "application/json")
        .build()

    val response = client.newCall(request).execute()
    val responseBody = response.body?.string()
    val token = parseTokenFromResponse(responseBody) // Extract token from response

    response.close()

    return token ?: throw IllegalStateException("Token not retrieved")
}

// Function to parse token from response
fun parseTokenFromResponse(responseBody: String?): String? {
    return try {
        val json = JSONObject(responseBody)
        json.getString("access_token")
    } catch (e: JSONException) {
        null
    }
}

fun processAssessments(connection: Connection, token: String) {
    val assessments: List<AssessmentData>
    try {
        assessments = getAssessments(connection)
    } catch (e: SQLException) {
        logger.severe("Error retrieving assessments: ${e.message}")
        return
    }

    val assessmentsGroupedById = assessments.groupBy { it.assessmentId }

    // Log retrieved assessments
    logger.info("Retrieved assessments: $assessmentsGroupedById")

    for ((assessmentId, invoices) in assessmentsGroupedById) {
        val invoiceIds = invoices.map { it.invoiceId }
        val claimType = invoices.first().claimType
        sendRequest(assessmentId, invoiceIds, claimType, token, connection) // Pass connection to sendRequest
    }
}

fun getAssessments(connection: Connection): List<AssessmentData> {
    val assessments = mutableListOf<AssessmentData>()
    val query = """
        SELECT DISTINCT TOP 10 a.assessmentId as assessment_id, i.InvoiceId as invoice_id, case when i.AdmissionStatus='Out Patient' then 'opd' else 'ipd' end as claimType 
        FROM ClaimAssessment a 
        JOIN claimtreatment t on a.AssessmentId = t.assessmentid 
        JOIN claimtreatmentinvoice i on i.treatmentid = t.treatmentid 
        JOIN claimtreatmentinvoiceline l on i.invoiceid = l.invoiceid
        JOIN indexinfo inx on index9 = CAST(a.assessmentId as varchar(26))
        left Join IassistSubmittedClaims ail on ail.assessmentid=a.assessmentid and ail.InvoiceID =i.invoiceid
        WHERE InvoiceStatus = 'Loaded'
        AND InvoiceEntity IN (116808, 116692, 116748, 116760, 116948, 116858, 116932, 148744)
        AND treatmentDate >= '2023-04-01'
        --and a.AssessmentId in (3479334,3773855,3819853)
        and ail.invoiceid is null and ail.invoiceid is null
         
        ORDER BY a.assessmentId ASC 
    """.trimIndent()

    // Log the executed SQL statement
    logger.info("Executing SQL query: $query")

    try {
        val statement = connection.prepareStatement(query)
        val resultSet = statement.executeQuery()

        while (resultSet.next()) {
            val assessmentId = resultSet.getString("assessment_id")
            val invoiceId = resultSet.getString("invoice_id")
            val claimType = resultSet.getString("claimType")
            assessments.add(AssessmentData(assessmentId, invoiceId, claimType))

            // Log each fetched row
            logger.info("Fetched row: assessmentId=$assessmentId, invoiceId=$invoiceId, claimType=$claimType")
        }

        resultSet.close()
        statement.close()
    } catch (e: SQLException) {
        logger.severe("SQL error: ${e.message}")
        throw e
    }

    return assessments
}

data class AssessmentData(val assessmentId: String, val invoiceId: String, val claimType: String)

fun sendRequest(assessmentId: String, invoiceIds: List<String>, claimType: String, token: String, connection: Connection) {
    val mediaType = "application/json".toMediaType()
    val json = """
        {
            "assessment_id": "$assessmentId",
            "invoice_id": ${invoiceIds.map { "\"$it\"" }},
            "claim_type": "$claimType"
        }
    """.trimIndent()
    val body = json.toRequestBody(mediaType)

    // Log the request body
    logger.info("Sending request with body: $json")

    val request = Request.Builder()
        .url("http://192.168.100.79/iail/initiate")
        .post(body)
        .addHeader("Authorization", "JWT $token")
        .addHeader("Content-Type", "application/json")
        .build()

    try {
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        // Log the response
        logger.info("Received response: $responseBody")

        // Parse response
        val jsonResponse = JSONObject(responseBody)
        if (jsonResponse.has("success")) {
            insertSubmittedClaim(assessmentId, invoiceIds, connection)
        } else {
            throw Exception("Claim initiation failed: $responseBody")
        }

        response.close()
    } catch (e: Exception) {
        logger.severe("Error sending request: ${e.message}")
    }
}
fun insertSubmittedClaim(assessmentId: String, invoiceIds: List<String>, connection: Connection) {
    val insertQuery = "INSERT INTO IassistSubmittedClaims (AssessmentId, InvoiceId, TimeSend) VALUES (?, ?, getDate())"

    try {
        connection.autoCommit = false
        val preparedStatement = connection.prepareStatement(insertQuery)

        for (invoiceId in invoiceIds) {
            preparedStatement.setString(1, assessmentId)
            preparedStatement.setString(2, invoiceId)
            preparedStatement.addBatch()
        }

        preparedStatement.executeBatch()
        connection.commit()
        preparedStatement.close()

        // Log successful insertion
        logger.info("Inserted assessmentId $assessmentId and invoiceIds $invoiceIds into IassistSubmittedClaims")
    } catch (e: SQLException) {
        connection.rollback()
        logger.severe("Error inserting into IassistSubmittedClaims: ${e.message}")
        throw e
    } finally {
        connection.autoCommit = true
    }
}

