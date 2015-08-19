package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;
import java.lang.Integer;

public final class coronajobresources_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {

 

	private String tasksUrl = null;
	private String jobUrl = null;
	private boolean hasProxy = false;

    private String getProxyUrl(String proxyPath, String params) {
      return proxyPath + (hasProxy ? "&" : "?") + params;
    }

  private static final JspFactory _jspxFactory = JspFactory.getDefaultFactory();

  private static java.util.Vector _jspx_dependants;

  private org.apache.jasper.runtime.ResourceInjector _jspx_resourceInjector;

  public Object getDependants() {
    return _jspx_dependants;
  }

  public void _jspService(HttpServletRequest request, HttpServletResponse response)
        throws java.io.IOException, ServletException {

    PageContext pageContext = null;
    HttpSession session = null;
    ServletContext application = null;
    ServletConfig config = null;
    JspWriter out = null;
    Object page = this;
    JspWriter _jspx_out = null;
    PageContext _jspx_page_context = null;

    try {
      response.setContentType("text/html; charset=UTF-8");
      pageContext = _jspxFactory.getPageContext(this, request, response,
      			null, true, 8192, true);
      _jspx_page_context = pageContext;
      application = pageContext.getServletContext();
      config = pageContext.getServletConfig();
      session = pageContext.getSession();
      out = pageContext.getOut();
      _jspx_out = out;
      _jspx_resourceInjector = (org.apache.jasper.runtime.ResourceInjector) application.getAttribute("com.sun.appserv.jsp.resource.injector");

      out.write('\n');
      out.write('\n');

  CoronaJobTracker tracker = (CoronaJobTracker) application.getAttribute("job.tracker");
  CoronaJobInProgress job = (CoronaJobInProgress) tracker.getJob();
  jobUrl = tracker.getProxyUrl("coronajobdetails.jsp");
  tasksUrl = tracker.getProxyUrl("coronajobtasks.jsp");
  hasProxy = (tasksUrl.indexOf('?') != -1);

  String jobId = request.getParameter("jobid");
  if (jobId == null) {
    out.println("<h2>Missing 'jobid'!</h2>");
    return;
  }
  JobID jobIdObj = JobID.forName(jobId);
  String kind = request.getParameter("kind");

  List<ResourceReport> resourceReportList =
      (job != null) ? tracker.getResourceReportList(kind) : null;

      out.write("\n\n<html>\n  <head>\n    <title>Hadoop ");
      out.print(kind);
      out.write(" resource list for ");
      out.print(jobId);
      out.write(" </title>\n    <link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n  </head>\n<body>\n<h1>Hadoop ");
      out.print(kind);
      out.write(" resource list for\n");

  out.print("<a href=\"" + jobUrl + "\">" + jobId + "</a>");

  if (job == null) {
    out.print("<b>Job not found.</b><br>\n");
    return;
  }

  if (resourceReportList.isEmpty()) {
    out.print("<b>No resource reports</b>");
  } else {
    out.print("<hr>");
    out.print("<h2>Resources</h2>");
    out.print("<center>");
    out.print("<table border=2 cellpadding=\"5\" cellspacing=\"2\">");
    out.print("<tr><td align=\"center\">Grant Id</td><td>Task</td></tr>");
    for (ResourceReport report : resourceReportList) {
      out.print("<tr><td>" + report.getGrantId() + "</td><td>" +
                report.getTaskAttemptString() + "</td>");
    }
    out.print("</table>");
    out.print("</center>");
  }

      out.write("\n\n<hr>\n");

  out.println("<a href=\"" + getProxyUrl(jobUrl, "jobid=" + jobId) + "\">Go back to JobDetails</a><br>");
  out.println(ServletUtil.htmlFooter());

      out.write('\n');
    } catch (Throwable t) {
      if (!(t instanceof SkipPageException)){
        out = _jspx_out;
        if (out != null && out.getBufferSize() != 0)
          out.clearBuffer();
        if (_jspx_page_context != null) _jspx_page_context.handlePageException(t);
      }
    } finally {
      _jspxFactory.releasePageContext(_jspx_page_context);
    }
  }
}
