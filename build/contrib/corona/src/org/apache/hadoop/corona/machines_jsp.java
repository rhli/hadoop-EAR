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

public final class machines_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  public void generateHostTable(JspWriter out, NodeManager nm, String type, String resourceType)
        throws IOException {
    out.print("<center>\n");
    Collection<String> nodes = null;
    if ("alive".equals(type)) {
      List<String> aliveNodes = nm.getAliveNodes();
      Collections.sort(aliveNodes);
      nodes = aliveNodes;
      out.println("<h2>Alive Nodes</h2>");
    } else if ("blacklisted".equals(type)) {
      List<String> blacklistedNodes = nm.getFaultManager().getBlacklistedNodes();
      Collections.sort(blacklistedNodes);
      nodes = blacklistedNodes;
      out.println("<h2>Blacklisted Nodes</h2>");
    } else if ("excluded".equals(type)) {
      // Sorting using TreeSet.
      Set<String> excludedNodes = new TreeSet<String>(nm.getExcludedNodes());
      nodes = excludedNodes;
      out.println("<h2>Excluded Nodes</h2>");
    } else if ("all".equals(type)) {
      Set<String> allNodes = new TreeSet<String>(nm.getAllNodes());
      nodes = allNodes;
      out.println("<h2>All Nodes</h2>");
    } else if ("free".equals(type)) {
      List<String> freeNodes = new ArrayList<String>();
      try {
        freeNodes = nm.getFreeNodesForType(ResourceType.valueOf(resourceType));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Cannot correctly parse resource type " +
            resourceType + ", must be one of " +
            Arrays.toString(ResourceType.values()));
      }
      Collections.sort(freeNodes);
      nodes = freeNodes;
      out.println("<h2>Free " + resourceType + " Nodes</h2>");
    } else {
      return;
    }
    out.print("<table border=\"2\" cellpadding=\"5\" cellspacing=\"2\">\n");
    out.print("<tr>");
    out.print("<td><b>Host Name</b></td></tr>\n");
    for (String node: nodes) {
      out.print("<td>" + node + "</td></tr>\n");
    }
    out.print("</table>\n");
    out.print("</center>\n");
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

  ClusterManager cm = (ClusterManager) application.getAttribute("cm");
  NodeManager nm = cm.getNodeManager();
  String cmHostName = StringUtils.simpleHostname(cm.getHostName());
  String type = request.getParameter("type");
  String resourceType = request.getParameter("resourceType");

      out.write('\n');
      out.write('\n');
      out.write("\n\n<html>\n<head>\n<title>");
      out.print( cmHostName );
      out.write(" Corona Machine List </title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/tablesorter/style.css\">\n<script type=\"text/javascript\" src=\"/static/jquery-1.7.1.min.js\"></script>\n<script type=\"text/javascript\" src=\"/static/tablesorter/jquery.tablesorter.js\"></script>\n<script type=\"text/javascript\" src=\"/static/tablesorter/jobtablesorter.js\"></script>\n</head>\n<body>\n\n");

  if ("alive".equals(type) ||
      "blacklisted".equals(type) ||
      "excluded".equals(type) ||
      "all".equals(type) ||
      "free".equals(type)) {
    generateHostTable(out, nm, type, resourceType);
  } else {
    out.println("Unknown type " + type);
  }

      out.write('\n');
      out.write('\n');

out.println(ServletUtil.htmlFooter());

      out.write('\n');
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
