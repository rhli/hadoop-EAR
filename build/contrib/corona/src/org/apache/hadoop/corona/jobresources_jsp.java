package org.apache.hadoop.corona;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.text.*;
import java.util.*;
import org.apache.hadoop.corona.*;
import org.apache.hadoop.util.ServletUtil;
import org.apache.hadoop.util.StringUtils;
import java.text.SimpleDateFormat;

public final class jobresources_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


	private Session mySession = null;

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

  ClusterManager cm = (ClusterManager) application.getAttribute("cm");
  NodeManager nm = cm.getNodeManager();
  SessionManager sm = cm.getSessionManager();
  String cmHostName = StringUtils.simpleHostname(cm.getHostName());
  String id = request.getParameter("id");
  mySession = sm.getSession(id);

      out.write("\n\n<html>\n<head>\n<title>");
      out.print( cmHostName );
      out.write(" Corona Cluster Manager Job Resources</title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n</head>\n<body>\n\n<h1>");
      out.print( cmHostName );
      out.write(" Resources for session ");
      out.print(id);
      out.write("</h1>\n<b>Started:</b> ");
      out.print( new Date(cm.getStartTime()));
      out.write("<br>\n<hr>\n\n");

  DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
  if (mySession == null) {
    out.print("<b>Session not found.</b><br>\n");
    return;
  }

  List<GrantReport> grantReportList = null;
  synchronized (mySession) {
    grantReportList = mySession.getGrantReportList();
  }
  if (grantReportList.isEmpty()) {
    out.print("<b>No grant reports</b>");
  } else {
    out.print("<h2>Grants</h2>");
    out.print("<center>");
    out.print("<table border=2 cellpadding=\"5\" cellspacing=\"2\">");
    out.print("<tr><td align=\"center\">Grant Id</td><td>Address</td>" +
              "<td>Type</td><td>Granted Time</td></tr>");
    for (GrantReport report : grantReportList) {
      out.print("<tr><td>" + report.getGrantId() + "</td><td>" +
      	        report.getAddress() + "</td><td>" +
                report.getType() + "</td><td>" +
                dateFormat.format(report.getGrantedTime()) + "</td>");
    }
    out.print("</table>");
    out.print("</center>");
  }

      out.write('\n');
      out.write('\n');

  out.println("<a href=\"cm.jsp\"" +
              "\">Go back to the Cluster Manager</a><br>");
  out.println("</body></html>");

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
