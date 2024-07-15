package com.example

import java.io.IOException
import javax.servlet.ServletException
import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@WebServlet(name = "MyServlet", urlPatterns = ["/processAssessments"])
class MyServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        // Your logic to call processAssessments or other methods
        resp.writer.println("Kotlin!")
    }

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        // Handle POST request
    }
}
