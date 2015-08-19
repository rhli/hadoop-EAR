package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.io.InputStreamReader;
import java.util.*;
import org.apache.hadoop.raid.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.DistributedFileSystem.*;

public final class raidfsck_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  String error = null;
  final static int FILE_DISPLAY_LIMIT = 500;
  public BufferedReader runFsck(RaidNode raidNode, 
      String dir, boolean listRecoverableFiles) throws Exception {
    try {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      PrintStream ps = new PrintStream(bout, true);
      RaidShell shell = new RaidShell(raidNode.getConf(), ps);
      String[] arguments;
      if (listRecoverableFiles) {
        arguments = new String[] {"-fsck", dir, "-listrecoverablefiles"};
      } else {
        arguments = new String[] {"-fsck", dir};
      }
      int res = ToolRunner.run(shell, arguments);
      ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
      shell.close();
      return new BufferedReader(new InputStreamReader(bin));
    } catch (Exception e) {
      error = e.getMessage();
      return null;
    }
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

  RaidNode raidNode = (RaidNode) application.getAttribute("raidnode");
  String name = raidNode.getHostName();
  name = name.substring(0, name.indexOf(".")).toUpperCase();

      out.write("\n\n<html>\n  <head>\n    <title>");
      out.print(name );
      out.write(" Hadoop RaidNode Administration</title>\n    <link rel=\"stylesheet\" type=\"text/css\" href=\"static/hadoop.css\">\n  </head>\n<body>\n<h1>");
      out.print(name );
      out.write(" Hadoop RaidNode Administration</h1>\n<b>Started:</b> ");
      out.print( new Date(raidNode.getStartTime()));
      out.write("<br>\n<b>Version:</b> ");
      out.print( VersionInfo.getVersion());
      out.write(",\n                r");
      out.print( VersionInfo.getRevision());
      out.write("<br>\n<b>Compiled:</b> ");
      out.print( VersionInfo.getDate());
      out.write(" by \n                 ");
      out.print( VersionInfo.getUser());
      out.write("<br>\n<hr>\n");
 
out.print("<h2>Raid Corrupt Files</h2>");

      out.write('\n');
      out.write('\n');

  String dir = request.getParameter("path");
  if (dir == null || dir.length() == 0) {
    dir = "/";
  }
  String recoverableStr = request.getParameter("recoverable");
  int recoverable = 0;
  try {
    if (recoverableStr != null && recoverableStr.length() > 0) {
      recoverable = Integer.valueOf(recoverableStr);
    } 
  } catch (NumberFormatException e) {
    error = e.getMessage();
  }
  BufferedReader reader = runFsck(raidNode, dir, recoverable != 0);
  if (error != null) {

      out.write("\n    ");
      out.print(error);
      out.write(" <br>\n");

  } else {
    String file = null;
    int total = 0;
    while (reader != null && (file = reader.readLine()) != null 
        && total < FILE_DISPLAY_LIMIT) {
      total++;
      out.println(file + "<br>");
    }

      out.write("\n    <p>\n      <b>Total:</b> At least ");
      out.print(total);
      out.write(" corrupt file(s).\n    </p>\n");

  }

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
