package org.apache.hadoop.corona;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.*;
import java.text.SimpleDateFormat;
import org.apache.hadoop.mapred.JobHistory.*;
import org.apache.hadoop.conf.Configuration;

public final class coronajobtaskshistory_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  private static SimpleDateFormat dateFormat =
                                    new SimpleDateFormat("yyyy/MM/dd HH:mm:ss") ;


  private void printTask(String jobid, String logFile,
      JobHistory.TaskAttempt attempt, int taskAttempts, JspWriter out)
      throws IOException{
    out.print("<tr>");
    out.print("<td>" + "<a href=\"coronataskdetailshistory.jsp?jobid=" + jobid +
          "&logFile="+ logFile +"&taskid="+attempt.get(Keys.TASKID)+"\">" +
          attempt.get(Keys.TASKID) + "</a></td>");
    out.print("<td>" + taskAttempts + "</td>");
    out.print("<td>" + StringUtils.getFormattedTimeWithDiff(dateFormat,
          attempt.getLong(Keys.START_TIME), 0 ) + "</td>");
    out.print("<td>" + StringUtils.getFormattedTimeWithDiff(dateFormat,
          attempt.getLong(Keys.FINISH_TIME),
          attempt.getLong(Keys.START_TIME) ) + "</td>");
    out.print("<td>" + attempt.get(Keys.ERROR) + "</td>");
    out.print("</tr>");
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
      out.write('\n');
      out.write('\n');

  String jobid = request.getParameter("jobid");
  String logFile = request.getParameter("logFile");
  String encodedLogFileName = JobHistory.JobInfo.encodeJobHistoryFilePath(logFile);
  String taskStatus = request.getParameter("status");
  String taskType = request.getParameter("taskType");

  Path jobFile = new Path(encodedLogFileName);
  FileSystem fs = jobFile.getFileSystem((Configuration) application.getAttribute("conf"));

  JobInfo job = JSPUtil.getJobInfo(request, fs);
  Map<String, JobHistory.Task> tasks = job.getAllTasks();

      out.write("\n<html>\n<head>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/tablesorter/style.css\">\n<script type=\"text/javascript\" src=\"/static/jquery-1.7.1.min.js\"></script>\n<script type=\"text/javascript\" src=\"/static/tablesorter/jquery.tablesorter.js\"></script>\n<script type=\"text/javascript\" src=\"/static/tablesorter/jobtablesorter.js\"></script>\n</head>\n<body>\n<h2>");
      out.print(taskStatus);
      out.write(' ');
      out.print(taskType );
      out.write(" task list for <a href=\"coronajobdetailshistory.jsp?jobid=");
      out.print(jobid);
      out.write("&&logFile=");
      out.print(encodedLogFileName);
      out.write('"');
      out.write('>');
      out.print(jobid );
      out.write(" </a></h2>\n<center>\n<table border=\"2\" cellpadding=\"5\" cellspacing=\"2\" class=\"tablesorter\">\n<thead>\n<tr><th>Task Id</th><th>Task Attempts</th><th>Start Time</th>\n    <th>Finish Time<br/></th><th>Error</th></tr>\n</thead>\n<tbody>\n");

  for (JobHistory.Task task : tasks.values()) {
    if (taskType.equals(task.get(Keys.TASK_TYPE))){
      Map <String, TaskAttempt> taskAttempts = task.getTaskAttempts();
      for (JobHistory.TaskAttempt taskAttempt : taskAttempts.values()) {
        if (taskStatus.equals(taskAttempt.get(Keys.TASK_STATUS)) ||
          taskStatus.equals("all")){
          printTask(jobid, encodedLogFileName, taskAttempt, taskAttempts.size(),
                    out);
        }
      }
    }
  }

      out.write("\n</tbody>\n</table>\n");
      out.write("\n</center>\n</body>\n</html>\n");
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
