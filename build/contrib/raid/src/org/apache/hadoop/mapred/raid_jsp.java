package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.raid.*;
import org.apache.hadoop.raid.StatisticsCollector;
import org.apache.hadoop.util.*;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.DistributedFileSystem.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.raid.DistBlockIntegrityMonitor.CorruptFileCounter;
import java.lang.Integer;
import java.text.SimpleDateFormat;

public final class raid_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  private String td(String s) {
    return JspUtils.td(s);
  }

  private String tr(String s) {
    return JspUtils.tr(s);
  }

  private String table(String s) {
    return JspUtils.tableSimple(s);
  }
  private long now() {
    return System.currentTimeMillis();
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

  RaidNode raidNode = (RaidNode) application.getAttribute("raidnode");
  StatisticsCollector stats = (StatisticsCollector) raidNode
      .getStatsCollector();
  PurgeMonitor purge = raidNode.getPurgeMonitor();
  CorruptFileCounter counter = raidNode.getCorruptFileCounter();
  PlacementMonitor place = raidNode.getPlacementMonitor();
  DiskStatus ds = ((DistributedFileSystem)FileSystem.get(raidNode.getConf()))
                     .getClient().getNSDiskStatus();
  String name = raidNode.getHostName();
  name = name.substring(0, name.indexOf(".")).toUpperCase();

      out.write('\n');
      out.write("\n\n<html>\n  <head>\n    <title>");
      out.print(name);
      out.write(" Hadoop RaidNode Administration</title> <link rel=\"stylesheet\" type=\"text/css\" href=\"static/hadoop.css\">\n  </head>\n<body>\n<h1>");
      out.print(name);
      out.write(" Hadoop RaidNode Administration</h1>\n<b>Started:</b> ");
      out.print(new Date(raidNode.getStartTime()));
      out.write("<br>\n<b>Version:</b> ");
      out.print(VersionInfo.getVersion());
      out.write(",\n                r");
      out.print(VersionInfo.getRevision());
      out.write("<br>\n<b>Compiled:</b> ");
      out.print(VersionInfo.getDate());
      out.write(" by\n                 ");
      out.print(VersionInfo.getUser());
      out.write("<br>\n<hr>\n<h2>RAID Summary </h2>\n");

  String total = StringUtils.byteDesc(ds.getCapacity());
  String used = StringUtils.byteDesc(ds.getDfsUsed());
  String saving = StringUtils.byteDesc(stats.getSaving());
  String doneSaving = StringUtils.byteDesc(stats.getDoneSaving());
  String repl = StringUtils
      .limitDecimalTo2(stats.getEffectiveReplication());
  String lastUpdate =
      StringUtils.formatTime(now() - stats.getLastUpdateTime()) + " ago";
  String updateUsed = StringUtils.formatTime(stats.getUpdateUsedTime());
  Thread.State state = raidNode.getStatsCollectorState();
  String filesScanned = StringUtils.humanReadableInt(stats
      .getFilesScanned());
  String tableStr = "";
  if (stats.getLastUpdateTime() != 0L) {
    tableStr += tr(td("Effective Replication") + td(":") + td(repl));
    tableStr += tr(td("Total") + td(":") + td(total));
    tableStr += tr(td("Namespace Used") + td(":") + td(used));
    tableStr += tr(td("Saving") + td(":") + td(saving));
    tableStr += tr(td("Done Saving") + td(":") + td(doneSaving));
    tableStr += tr(td("File Scanned") + td(":") + td(filesScanned));
    tableStr += tr(td("Update Used") + td(":") + td(updateUsed));
    tableStr += tr(td("Last Update") + td(":") + td(lastUpdate));
  } else {
    tableStr += tr(td("Total") + td(":") + td(total));
    tableStr += tr(td("Namespace Used") + td(":") + td(used));
    tableStr += tr(td("File Scanned") + td(":") + td(filesScanned));
  }
  out.print(table(tableStr));

      out.write("\n<hr>\n");

  for (Codec codec: Codec.getCodecs()) {
    out.print("\n<h2>" + codec.id + " (" +
              (codec.isDirRaid?"Directory-level":
                               "File-level") +
              ") " + "</h2>\n");
    Statistics codeStats = stats.getRaidStatistics(codec.id);
    String paritySize, estParitySize;
    if (codeStats != null) {
      out.print(codeStats.htmlTable());
      saving = StringUtils.byteDesc(codeStats.getSaving(raidNode.getConf()));
      doneSaving = StringUtils.byteDesc(codeStats.getDoneSaving(raidNode.getConf()));
      repl = StringUtils.limitDecimalTo2(codeStats.getEffectiveReplication());
      paritySize = StringUtils.byteDesc(codeStats.getParityCounters()
          .getNumBytes());
      estParitySize = StringUtils.byteDesc(codeStats.getEstimatedParitySize());
      tableStr = "";
      tableStr += tr(td("Effective Replication") + td(":") + td(repl));
      tableStr += tr(td("Saving") + td(":") + td(saving));
      tableStr += tr(td("Done Saving") + td(":") + td(doneSaving));
      tableStr += tr(td("Parity / Expected") + td(":")
          + td(paritySize + " / " + estParitySize));
      out.print(table(tableStr));
    } else {
      out.print("Wait for collecting");
    }
    out.print("\n<hr>\n");
  }

      out.write("\n<h2>Purge Progress</h2>\n");

  out.print(purge.htmlTable());

      out.write("\n<hr>\n<h2>Block Placement</h2>\n");

  if (place.lastUpdateTime() != 0) {
    out.println("Block Placement Per Node");
    out.print(place.htmlTable());
    out.println();
    out.println("Block Placement Per Rack");
    out.print(place.htmlTablePerRack());
    tableStr = "";
    lastUpdate =
        StringUtils.formatTime(now() - place.lastUpdateTime()) + " ago";
    updateUsed = StringUtils.formatTime(place.lastUpdateUsedTime());
    String queueSize = StringUtils.humanReadableInt(place
        .getMovingQueueSize());
    tableStr += tr(td("Moving in Progress") + td(":") + td(queueSize));
    tableStr += tr(td("Update Used") + td(":") + td(updateUsed));
    tableStr += tr(td("Last Update") + td(":") + td(lastUpdate));
    out.print(table(tableStr));
  } else {
    String queueSize = StringUtils.humanReadableInt(place
        .getMovingQueueSize());
    tableStr = tr(td("Moving in Progress") + td(":") + td(queueSize));
    out.print(table(tableStr));
  }

      out.write('\n');

  String metricsUrl = raidNode.getReadReconstructionMetricsUrl();
  if (metricsUrl != null && !metricsUrl.trim().equals("")) {
    out.print("<hr>\n");
    out.print("<h2>Read Reconstruction Metrics "
        + JspUtils.link("see details", metricsUrl)
        + "</h2>");
  }

      out.write("\n<h2>Missing Blocks</h2>\n");

  out.print(counter.getMissingBlksHtmlTable());

      out.write('\n');
      out.write('\n');

  BlockIntegrityMonitor.Status status = null;
  boolean unsupported = false;
  try {
    status = raidNode.getBlockIntegrityMonitorStatus();
  } catch (UnsupportedOperationException e) {
    unsupported = true;
  }
  if (!unsupported) {
    out.print("<hr>\n");
    out.print("<h2>Block Fixing "
        + JspUtils.link("see details", "blockfixer.jsp") + "</h2>");
    if (status != null) {
      out.print(status.toHtml(0));
    } else {
      out.print("Wait for collecting");
    }
  }

      out.write('\n');

  out.print("<hr>\n");
  out.print("<h2>Raid Jobs "
      + JspUtils.link("see details", "jobmonitor.jsp") + "</h2>");

      out.write('\n');

  out.print("<hr>\n");
  out.print("<h2>Corrupt Files "
      + JspUtils.link("see details", "raidfsck.jsp") + "</h2>");

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
