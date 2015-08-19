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
import org.apache.hadoop.util.*;
import org.apache.hadoop.util.ServletUtil;
import org.apache.hadoop.util.StringUtils;
import org.json.*;

public final class retired_005fjson_jsp extends org.apache.jasper.runtime.HttpJspBase
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

  WebUtils.JspParameterFilters filters = WebUtils.getJspParameterFilters(
      request.getParameter("users"),
      request.getParameter("poolGroups"),
      request.getParameter("poolInfos"));

  DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
  ClusterManager cm = (ClusterManager) application.getAttribute("cm");
  NodeManager nm = cm.getNodeManager();
  SessionManager sm = cm.getSessionManager();

  JSONObject result = new JSONObject();
  JSONArray array = new JSONArray();
  
  Collection<ResourceType> resourceTypes = nm.getResourceTypes();
  Collection<RetiredSession> retiredSessions = sm.getRetiredSessions();
  synchronized (retiredSessions) {
    for (RetiredSession s : retiredSessions) {
      PoolInfo poolInfo = s.getPoolInfo();
      if (!WebUtils.showUserPoolInfo(
          s.getUserId(),
          filters.getUserFilterSet(),
          s.getPoolInfo(),
          filters.getPoolGroupFilterSet(), filters.getPoolInfoFilterSet())) {
        continue;
      }

      JSONArray row = new JSONArray();
      // fixed columns: Id/Url + Userid + Name + Status
      String url =
          (s.getUrl() == null || s.getUrl().length() == 0) ? s.getSessionId()
              :
              "<a href=\"" + s.getUrl() + "\">" + s.getSessionId() + "</a>";
      row.put(url);
      row.put(dateFormat.format(new Date(s.getStartTime())));
      row.put(dateFormat.format(new Date(s.getDeletedTime())));
      row.put(s.getName());
      row.put(s.getUserId());
      row.put(poolInfo.getPoolGroupName());
      row.put(poolInfo.getPoolName() == null ? "-" :
          poolInfo.getPoolName());
      row.put(s.getPriority());
      row.put(s.getStatus());

      // variable columns
      for (ResourceType resourceType : resourceTypes) {
        int totalTypes =
            s.getFulfilledRequestCountForType(resourceType);
        row.put(totalTypes);
        
        if (resourceType == ResourceType.JOBTRACKER) {
          continue;
        }
        
        List<Long> resourceUsage = s.getResourceUsageForType(resourceType);
        if (resourceUsage != null) {
          for (long val:resourceUsage) {
            row.put(StringUtils.humanReadableInt(val));
          }
        } else {
          row.put("0 B");
          row.put("0 B");
          row.put("0 B");
        }
        
      }
      array.put(row);
    }
  }

  result.put("aaData", array);
  response.setContentType("application/json");
  response.setHeader("Cache-Control", "no-store");
  out.print(result);

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
