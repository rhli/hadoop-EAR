package org.apache.hadoop.hdfs.qjournal.server;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.hadoop.hdfs.qjournal.server.JournalNodeJspHelper.QJMStatus;
import org.apache.hadoop.hdfs.qjournal.server.JournalNodeJspHelper.StatsDescriptor;
import org.apache.hadoop.hdfs.qjournal.server.*;
import org.apache.hadoop.util.*;
import java.net.InetSocketAddress;

public final class qjmstatus_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {

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
      response.setContentType("text/html");
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

  JournalNode jn = (JournalNode)application.getAttribute(JournalNodeHttpServer.JN_ATTRIBUTE_KEY);
  JournalNodeJspHelper helper = new JournalNodeJspHelper(jn);
  QJMStatus status = helper.generateQJMStatusReport();

      out.write("\n<html>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<title>Quorum Journal Manager</title>\n<body>\n<h1>Quorum Journal Manager: ");
      out.print( helper.getName());
      out.write(" </h1>\n");

  out.print("<h3> Journal nodes: </h3>");
  out.print("<div id=\"dfstable\">");
  out.println(JournalNodeJspHelper.getNodeReport(status));

      out.write('\n');

  out.print("<h3> Journals: </h3>");
  out.print("<div id=\"dfstable\">");
  out.println(JournalNodeJspHelper.getJournalReport(status));

      out.write('\n');

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
