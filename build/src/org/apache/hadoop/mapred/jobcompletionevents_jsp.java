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
import java.text.SimpleDateFormat;

public final class jobcompletionevents_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {

 static SimpleDateFormat dateFormat = new SimpleDateFormat("d-MMM-yyyy HH:mm:ss") ; 
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

  JobTracker tracker = (JobTracker) application.getAttribute("job.tracker");
  String trackerName =
           StringUtils.simpleHostname(tracker.getJobTrackerMachine());
  String jobid = request.getParameter("jobid");
  String pagenum = request.getParameter("pagenum");
  int pnum = Integer.parseInt(pagenum);
  int next_page = pnum+1;
  int numperpage = 2000;
  JobID jobIdObj = JobID.forName(jobid);
  JobInProgress job = (JobInProgress) tracker.getJob(jobIdObj);
  JobProfile profile = (job != null) ? (job.getProfile()) : null;
  JobStatus status = (job != null) ? (job.getStatus()) : null;
  TaskCompletionEvent[] reports = null;
  int start_index = (pnum - 1) * numperpage;
  int end_index = start_index + numperpage;
  int report_len = 0;
  reports = (job == null)
            ? null
            : tracker.getTaskCompletionEvents(jobIdObj, start_index, end_index - start_index);

      out.write("\n\n<html>\n  <head>\n    <title>Hadoop task completion event list for ");
      out.print(jobid);
      out.write(" on ");
      out.print(trackerName);
      out.write("</title>\n    <link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n  </head>\n<body>\n<h1>Hadoop task completion event task list for\n<a href=\"jobdetails.jsp?jobid=");
      out.print(jobid);
      out.write('"');
      out.write('>');
      out.print(jobid);
      out.write("</a> on\n<a href=\"jobtracker.jsp\">");
      out.print(trackerName);
      out.write("</a></h1>\n");

  if (job == null) {
      out.print("<b>Job " + jobid + " not found.</b><br>\n");
    return;
  }
  if (reports == null) {
      out.print("<b>Report for " + jobid + " not found.</b><br>\n");
    return;
  }
  report_len = reports.length;

  if (report_len == 0 ) {
    out.print("<b>No such events</b>");
  } else {
    out.print("<hr>");
    out.print("<h2>Tasks</h2>");
    out.print("<center>");
    out.print("<table border=2 cellpadding=\"5\" cellspacing=\"2\">");
    out.print("<tr><td align=\"center\">ID</td><td>Event ID</td><td>Task ID</td><td>ID within Job</td>" +
              "<td>Type</td><td>Status</td></tr>");

    for (int i = 0; i < report_len; i++) {
      TaskCompletionEvent report = reports[i];
      if (report == null) {
        out.print("<tr>");
        out.print("<td>null</td>");
        out.print("<td>null</td>");
        out.print("<td>null</td>");
        out.print("<td>null</td>");
        out.print("<td>null</td>");
        out.print("<td>null</td>");
      }
      String url = report.getTaskTrackerHttp();
      out.print("<tr>");
      out.print("<td><a href=\"" + url + "\">" + (start_index + i) +"</a></td>");
      out.print("<td>" + report.getEventId() + "</td>");
      out.print("<td>" + report.getTaskId() + "</td>");
      out.print("<td>" + report.idWithinJob() + "</td>");
      out.print("<td>" + (report.isMapTask() ? "Map" : "Reduce") + "</td>");
      out.print("<td>" + report.getTaskStatus().name() + "</td>");
    }
    out.print("</table>");
    out.print("</center>");
  }
  if (report_len == end_index - start_index) {
    out.print("<div style=\"text-align:right\">" +
              "<a href=\"jobcompletionevents.jsp?jobid="+ jobid +
              "&pagenum=" + next_page +
              "\">" + "Next" + "</a></div>");
  }
  if (start_index != 0) {
      out.print("<div style=\"text-align:right\">" +
                "<a href=\"jobcompletionevents.jsp?jobid="+ jobid +
                "&pagenum=" + (pnum -1) + "\">" + "Prev" + "</a></div>");
  }

      out.write("\n\n<hr>\n<a href=\"jobtracker.jsp\">Go back to JobTracker</a><br>\n<a href=\"http://lucene.apache.org/hadoop\">Hadoop</a>, 2007.<br>\n</body>\n</html>\n");
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
