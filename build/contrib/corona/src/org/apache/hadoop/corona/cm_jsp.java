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

public final class cm_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  public void generateSummaryTable(JspWriter out, NodeManager nm,
      SessionManager sm)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("<table id=\"summaryTable\" border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row with list of resource types
    sb.append("<thead><tr>");

    Collection<ResourceType> resourceTypes = nm.getResourceTypes();
    String[] perTypeColumns =
        { "Running", "Waiting", "TotalSlots", "FreeSlots" };

    sb.append(generateTableHeaderCells(
        WebUtils.convertResourceTypesToStrings(resourceTypes),
        perTypeColumns.length, 0, false));
    sb.append("<th colspan=3>Nodes</th>");
    sb.append("<th colspan=1>Session</th>");
    sb.append("</tr>\n");
    out.print(sb.toString());

    String typeColHeader =
        "<th>"
            + org.apache.commons.lang.StringUtils.join(perTypeColumns,
                "</th><th>") + "</th>";
    StringBuilder row = new StringBuilder("<tr>");
    for (ResourceType resourceType : resourceTypes) {
      row.append(typeColHeader);
    }

    // Nodes
    row.append("<th>Alive</th>");
    row.append("<th>Blacklisted</th>");
    row.append("<th>Excluded</th>");
    // Session
    row.append("<th>Running</th></tr></thead>\n");
    out.print(row.toString());

    row = new StringBuilder("<tbody><tr>");

    for (ResourceType resourceType : resourceTypes) {
      int waiting = sm.getPendingRequestCountForType(resourceType);
      int running = sm.getGrantCountForType(resourceType);
      int totalslots = nm.getMaxCpuForType(resourceType);
      int freeslots = totalslots - nm.getAllocatedCpuForType(resourceType);
      row.append("<td>" + running + "</td>");
      row.append("<td>" + waiting + "</td>");
      row.append("<td>" + totalslots + "</td>");
      row.append("<td><a href=\"machines.jsp?type=free&resourceType=" + resourceType + "\">" + freeslots + "</a></td>");
    }

    row.append("<td><a href=\"machines.jsp?type=alive\">" +
        nm.getAliveNodeCount() + "</a></td>");
    FaultManager fm = nm.getFaultManager();
    row.append("<td><a href=\"machines.jsp?type=blacklisted\">" +
        fm.getBlacklistedNodeCount() + "</a></td>");
    row.append("<td><a href=\"machines.jsp?type=excluded\">" +
        nm.getExcludedNodeCount() + "</a></td>");
    row.append("<td>" + sm.getRunningSessionCount() + "</td>");
    row.append("</tr></tbody>\n");
    row.append("</table>\n");

    out.print(row.toString());
    out.print("<br>");
  }

  public void generateActiveSessionTable(
      JspWriter out, NodeManager nm, SessionManager sm, Scheduler scheduler,
      Set<String> userFilterSet,
      Set<String> poolGroupFilterSet, Set<PoolInfo> poolInfoFilterSet,
      DateFormat dateFormat,
      boolean canKillSessions)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("<table id=\"activeTable\" border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row
    sb.append("<thead><tr>");
   
    // fixed headers  
    String[] fixedHeaders = { "Id", "Start Time", "Name", "User", "Pool Group",
            "Pool", "Job Priority" };;
    String[] fixedHeadersCanKill = { "", "Id", "Start Time", "Name", "User", "Pool Group",
            "Pool", "Job Priority" };
    if (canKillSessions) {
      fixedHeaders = fixedHeadersCanKill;
    } 
    sb.append(generateTableHeaderCells(Arrays.asList(fixedHeaders), 1, 2, false));
    
    // header per type
    Collection<ResourceType> resourceTypes = nm.getResourceTypes();
    String[] perTypeColumns = { "Running", "Waiting", "Total", "MaxMemory", "MaxInstMemory", "MaxRSSMemory" };
    sb.append(generateTableHeaderCells(
        WebUtils.convertResourceTypesToStrings(resourceTypes),
        perTypeColumns.length, 0, false));

    sb.append("</tr>\n");

    // populate sub-header row
    sb.append("<tr>");
    for (ResourceType resourceType : resourceTypes) {
      for (int i = 0; i < perTypeColumns.length; i++) {
        sb.append("<th>" + perTypeColumns[i] + "</th>");
        if (i == 2 && resourceType == ResourceType.JOBTRACKER) {
          break;
        }
      }
    }
    sb.append("</tr></thead><tbody>\n");
    out.print(sb.toString());
    out.print("</tbody></table><br>");
    
    if (canKillSessions) {
      out.print("<button id=\"killSession\" type=\"button\">Kill Session</button>");
    }
  }

  private String getPoolInfoTableData(Map<PoolInfo, PoolInfo> redirects,
                                      PoolInfo poolInfo) {
    StringBuffer sb = new StringBuffer();
    String redirectAttributes = "";
    if (redirects != null) {
      PoolInfo destination = redirects.get(poolInfo);
      if (destination != null) {
        redirectAttributes = " title=\"Redirected to " +
            PoolInfo.createStringFromPoolInfo(destination) +
            "\" class=\"ui-state-disabled\"";
      }
    }

    sb.append("<td" + redirectAttributes + ">" + poolInfo.getPoolGroupName() +
        "</td>");
    sb.append("<td" + redirectAttributes + ">" +
        (poolInfo.getPoolName() == null ? "-" : poolInfo.getPoolName()) +
        "</td>");
    return sb.toString();
  }

  public void generatePoolTable(
      JspWriter out, Scheduler scheduler, Collection<ResourceType> types,
      Set<String> poolGroupFilterSet, Set<PoolInfo> poolInfoFilterSet)
      throws IOException {
    List<String> metricsNames = new ArrayList<String>();
    for (PoolInfoMetrics.MetricName name :
        PoolInfoMetrics.MetricName.values()) {
      metricsNames.add(name.toString());
    }
    StringBuilder sb = new StringBuilder();
    sb.append("<table id=\"poolTable\" border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    ConfigManager configManager = scheduler.getConfigManager();
    Map<PoolInfo, PoolInfo> redirects = configManager.getRedirects();

    // Generate headers
    sb.append("<thead><tr>");
    sb.append("<th rowspan=2>Pool Group</th>");
    sb.append("<th rowspan=2>Pool</th>");
    sb.append("<th rowspan=2>Scheduling</th>");
    sb.append("<th rowspan=2>Preemptable</th>");
    sb.append("<th rowspan=2>RequestMax</th>");
    sb.append("<th rowspan=2>PoolPriority</th>");
    sb.append(generateTableHeaderCells(
        WebUtils.convertResourceTypesToStrings(types),
        metricsNames.size(), 0, false));
    sb.append("</tr>\n");

    sb.append("<tr>");
    for (int i = 0; i < types.size(); ++i) {
      for (String name : metricsNames) {
        sb.append("<th>" + name + "</th>");
      }
    }
    sb.append("</tr></thead><tbody>\n");
    sb.append("</tbody></table><br>");
    out.print(sb.toString());
  }

  private String generateTableHeaderCells(Collection<String> headers,
      int colspan, int rowspan,
      boolean useTableData) {
    StringBuilder sb = new StringBuilder();
    String tag = useTableData ? "td" : "th";
    StringBuilder joinFrag = new StringBuilder("<" + tag);
    if (colspan > 0) {
      joinFrag.append(" colspan=" + colspan);
    }
    if (rowspan > 0) {
      joinFrag.append(" rowspan=" + rowspan);
    }
    joinFrag.append(">");
    sb.append(joinFrag);
    sb.append(org.apache.commons.lang.StringUtils.join(headers, "</" + tag
        + ">" + joinFrag));
    sb.append("</" + tag + ">");
    return sb.toString();
  }

  public void generateRetiredSessionTable(JspWriter out, NodeManager nm,
      SessionManager sm, Set<String> userFilterSet,
      Set<String> poolGroupFilterSet, Set<PoolInfo> poolInfoFilterSet,
      DateFormat dateFormat)
      throws IOException {

    StringBuilder sb = new StringBuilder();
    sb.append("<table id=\"retiredTable\" border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");

    // populate header row
    sb.append("<thead><tr>");

    // fixed headers
    String[] fixedHeaders = { "Id", "Start Time", "Finished Time",
        "Name", "User", "Pool Group", "Pool", "Job Priority", "Status"};
    sb.append(generateTableHeaderCells(Arrays.asList(fixedHeaders), 1, 2, false));

    // header per type
    Collection<ResourceType> resourceTypes = nm.getResourceTypes();
    String[] perTypeColumns = { "Ran", "MaxMemory", "MaxInstMemory", "MaxRSSMemory" };
    sb.append(generateTableHeaderCells(
        WebUtils.convertResourceTypesToStrings(resourceTypes),
        perTypeColumns.length, 0, false));

    sb.append("</tr>\n");

    // populate sub-header row
    sb.append("<tr>");
    for (ResourceType resourceType : resourceTypes) {
      for (int i = 0; i < perTypeColumns.length; i++) {
        sb.append("<th>" + perTypeColumns[i] + "</th>");
        if (resourceType == ResourceType.JOBTRACKER) {
          break;
        }
      }
    }
    sb.append("</tr></thead><tbody>\n");
    out.print(sb.toString());
    out.print("</tbody></table><br>");
  }

  public String getDowntimeString(Date lastDowntime) {
    Long daysElapsed = (new Date().getTime() - lastDowntime.getTime()) /
                        (24*60*60*1000);
    return new String(lastDowntime.toString() +
                     " (" + daysElapsed + " days ago)");
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

  DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
  ClusterManager cm = (ClusterManager) application.getAttribute("cm");
  NodeManager nm = cm.getNodeManager();
  SessionManager sm = cm.getSessionManager();
  String cmHostName = StringUtils.simpleHostname(cm.getHostName());

  String toKillSessionId = request.getParameter("toKillSessionId");
  boolean canKillSessions = 
    WebUtils.isValidKillSessionsToken(request.getParameter("killSessionsToken"));
  
  if (toKillSessionId != null && canKillSessions) {
    String[] ids = toKillSessionId.split(" ");
    try {
      KillSessionsArgs killSessionsArgs = new KillSessionsArgs();
      killSessionsArgs.sessionIds = Arrays.asList(ids);
      killSessionsArgs.who = request.getRemoteHost();
      
      cm.killSessions(killSessionsArgs);
    } catch (Exception e) {
    }
  }

      out.write("\n\n<html>\n<head>\n<title>");
      out.print(cmHostName);
      out.write(" Corona Cluster Manager </title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/jquery/css/ui-lightness/jquery-ui-1.8.20.custom.css\"/>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/dataTables/css/jquery.dataTables.css\">\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/multiselect/jquery.multiselect.css\">\n<script type=\"text/javascript\" src=\"/static/jquery/js/jquery-1.7.2.min.js\"></script>\n<script type=\"text/javascript\" src=\"/static/jquery/js/jquery-ui-1.8.20.custom.min.js\"></script>\n<script type=\"text/javascript\" src=\"/static/cm.js\"></script>\n<script type=\"text/javascript\" src=\"/static/dataTables/js/jquery.dataTables.min.js\"></script>\n<script type=\"text/javascript\" src=\"/static/multiselect/jquery.multiselect.min.js\"></script>\n<style type=\"text/css\">\n  body {\n    font-family: Verdana,Arial,sans-serif;\n    font-size: 1.1em;\n  }\n  .ui-button {\n    font-family: Verdana,Arial,sans-serif;\n    font-size: .91em; /* ≅ 1/1.1 */\n  }\n</style>\n");
      out.write("</head>\n<body>\n\n<script type=\"text/javascript\"\n  src=\"/static/jqueryThemeRoller.js\">\n</script>\n<div id=\"switcher\"></div>\n\n<div id=\"quicklinks\">\n  <a href=\"#quicklinks\" onclick=\"toggle('quicklinks-list'); return false;\">Quick Links</a>\n  <ul id=\"quicklinks-list\">\n    <li><a href=\"#pools\">Pools</a></li>\n    <li><a href=\"#active_sessions\">Active Sessions</a></li>\n    <li><a href=\"#retired_sessions\">Retired Sessions</a></li>\n  </ul>\n</div>\n\n");

  String validationMessage =
    WebUtils.validateAttributeNames(request.getParameterNames());
  if (validationMessage != null) {

      out.write("\n\n<script type=\"text/javascript\">\n alert('");
      out.print(validationMessage);
      out.write("');\n</script>\n\n");

  }

      out.write("\n\n<h1>");
      out.print(cmHostName);
      out.write(" Corona Cluster Manager </h1>\n<b>Last Restarted:</b> ");
      out.print(new Date(cm.getLastRestartTime()));
      out.write("<br>\n<b>Last Downtime:</b> ");
      out.print(getDowntimeString(new Date(cm.getStartTime())));
      out.write("<br>\n<b>Version:</b> ");
      out.print(VersionInfo.getVersion());
      out.write(",\n                r");
      out.print(VersionInfo.getRevision());
      out.write("<br>\n<b>Compiled:</b> ");
      out.print(VersionInfo.getDate());
      out.write(" by\n                 ");
      out.print(VersionInfo.getUser());
      out.write("<br>\n<b>Safe Mode :</b> ");
      out.print(cm.getSafeMode() ? "ON" : "OFF");
      out.write('\n');
      out.write('\n');

  WebUtils.JspParameterFilters filters = WebUtils.getJspParameterFilters(
    request.getParameter("users"),
    request.getParameter("poolGroups"),
    request.getParameter("poolInfos"));
  out.print(filters.getHtmlOutput().toString());
  Scheduler scheduler = cm.getScheduler();
  List<String> poolGroups = new ArrayList<String>();
  List<String> poolInfos = new ArrayList<String>();
  for (PoolInfo poolInfo : scheduler.getPoolInfos()) {
    if (poolInfo.getPoolName() == null) {
      poolGroups.add(poolInfo.getPoolGroupName());
    } else {
      poolInfos.add(PoolInfo.createStringFromPoolInfo(poolInfo));
    }
  }

      out.write("\n\n<hr>\n<div id=\"toolbar\">\n<select id=\"poolGroupSelect\" name=\"poolGroupSelect\" multiple=\"multiple\">\n");

  for (String poolGroupString : poolGroups) {
    out.write("<option value=\""+ poolGroupString + "\">" + poolGroupString +
        "</option>\n");
  }

      out.write("\n</select>\n<input id=\"poolInfoInput\" placeholder=\"Enter a comma-separated list of pool infos\" style=\"width:300px;height=30px;\"/>\n<select id=\"poolInfoSelect\" name=\"poolInfoSelect\" multiple=\"multiple\">\n");

  for (String poolInfoString : poolInfos) {
    out.write("<option value=\""+ poolInfoString + "\">" + poolInfoString +
        "</option>\n");
  }

      out.write("\n</select>\n<button id=\"addFilter\" type=\"button\">Use selected filters</button>\n</div>\n<h2>Cluster Summary</h2>\n\n");

  generateSummaryTable(out, cm.getNodeManager(), cm.getSessionManager());

      out.write("\n\n<h2 id=\"pools\">Pools</h2>\n<button id=\"poolToggle\" type=\"button\">Show/Hide</button>\n");

  generatePoolTable(out, cm.getScheduler(), cm.getTypes(),
      filters.getPoolGroupFilterSet(), filters.getPoolInfoFilterSet());

      out.write("\n\n<h2 id=\"active_sessions\">Active Sessions</h2>\n<button id=\"activeToggle\" type=\"button\">Show/Hide</button>\n");

  generateActiveSessionTable(out, cm.getNodeManager(),
      cm.getSessionManager(), cm.getScheduler(),
      filters.getUserFilterSet(),
      filters.getPoolGroupFilterSet(),
      filters.getPoolInfoFilterSet(), dateFormat,
      canKillSessions);

      out.write("\n<h2 id=\"retired_sessions\">Retired Sessions</h2>\n<button id=\"retiredToggle\" type=\"button\">Show/Hide</button>\n");

  generateRetiredSessionTable(out, cm.getNodeManager(),
      cm.getSessionManager(),
      filters.getUserFilterSet(),
      filters.getPoolGroupFilterSet(),
      filters.getPoolInfoFilterSet(), dateFormat);

      out.write('\n');
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
