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

public final class exec_jsp extends org.apache.jasper.runtime.HttpJspBase
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

  ClusterManager cm = (ClusterManager) application.getAttribute("cm");

      out.write("\n\n<html>\n<head>\n<title>");
      out.print( cm.getHostName() );
      out.write(" Corona Cluster Manager - exec </title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n</head>\n<body>\n\n");

  ConfigManager configManager = cm.getScheduler().getConfigManager();
  String req = (String) request.getParameter("req");
  out.println("req = " + ((req == null) ? "<none>" : req) + "<br>");
  if (req == null) {
    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
        "Bad request, set req parameter");
  } else if (req.equals("reloadServerPoolsConfig")) {
    String md5String = configManager.generatePoolsConfigIfClassSet();
    if (md5String == null) {
      response.sendError(HttpServletResponse.SC_EXPECTATION_FAILED,
          "Failed to generate a pools config");
    } else {
      if (configManager.reloadAllConfig(false)) {
        response.addHeader("md5sum", md5String);
        out.println("response = " + md5String + "<br>");
      } else {
        response.sendError(HttpServletResponse.SC_EXPECTATION_FAILED,
            "Success generating the pools config, but failed to reload" +
            " the config");
      }
    }
  } else {
    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Req " + req + " not supported");
  }

      out.write("\n\n</body>\n</html>\n");
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
