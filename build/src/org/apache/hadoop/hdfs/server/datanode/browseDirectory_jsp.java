package org.apache.hadoop.hdfs.server.datanode;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.net.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.hdfs.server.namenode.*;
import org.apache.hadoop.hdfs.server.datanode.*;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.*;
import java.text.DateFormat;

public final class browseDirectory_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {


  static JspHelper jspHelper = new JspHelper();
  
  public void generateDirectoryStructure( JspWriter out, 
                                          HttpServletRequest req,
                                          HttpServletResponse resp) 
    throws IOException {
    String dir = req.getParameter("dir");
    if (dir == null || dir.length() == 0) {
      out.print("Invalid input");
      return;
    }
    boolean showUsage = false;
    if (req.getParameter("showUsage") != null) {
      showUsage = true;
    }
    
    String namenodeInfoPortStr = req.getParameter("namenodeInfoPort");
    int namenodeInfoPort = -1;
    if (namenodeInfoPortStr != null)
      namenodeInfoPort = Integer.parseInt(namenodeInfoPortStr);

    final String nnAddr = req.getParameter(JspHelper.NAMENODE_ADDRESS);
    if (nnAddr == null){
      out.print(JspHelper.NAMENODE_ADDRESS + " url param is null");
      return;
    }

    InetSocketAddress namenodeAddress = DFSUtil.getSocketAddress(nnAddr);
    DFSClient dfs = null;
    try {
      dfs = new DFSClient(namenodeAddress, jspHelper.conf);
    } catch (IOException ex) {
      // This probably means that the namenode is not out of safemode
      resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
    String target = dir;
    if (!dfs.exists(target)) {
      out.print("<h3>File or directory : " + target + " does not exist</h3>");
      JspHelper.printGotoForm(out, namenodeInfoPort, target, nnAddr);
    }
    else {
      if( !dfs.isDirectory(target) ) { // a file
        List<LocatedBlock> blocks = 
          dfs.namenode.getBlockLocations(dir, 0, 1).getLocatedBlocks();
	      
        LocatedBlock firstBlock = null;
        DatanodeInfo [] locations = null;
        if (blocks.size() > 0) {
          firstBlock = blocks.get(0);
          locations = firstBlock.getLocations();
        }
        if (locations == null || locations.length == 0) {
          out.print("Empty file");
        } else {
          DatanodeInfo chosenNode = jspHelper.bestNode(firstBlock);
          String fqdn = InetAddress.getByName(chosenNode.getHost()).
            getHostAddress();
          String datanodeAddr = chosenNode.getName();
          int datanodePort = Integer.parseInt(
                                              datanodeAddr.substring(
                                                                     datanodeAddr.indexOf(':') + 1, 
                                                                     datanodeAddr.length())); 
          String redirectLocation = "http://" + NetUtils.toIpPort(fqdn, chosenNode.getInfoPort()) + 
            "/browseBlock.jsp?blockId=" +
            firstBlock.getBlock().getBlockId() +
            "&blockSize=" + firstBlock.getBlock().getNumBytes() +
            "&genstamp=" + firstBlock.getBlock().getGenerationStamp() +
            "&filename=" + URLEncoder.encode(dir, "UTF-8") + 
            "&datanodePort=" + datanodePort + 
            "&namenodeInfoPort=" + namenodeInfoPort +
            JspHelper.getUrlParam(JspHelper.NAMENODE_ADDRESS, nnAddr);
          resp.sendRedirect(redirectLocation);
        }
        return;
      }
      // directory
      FileStatus[] files = dfs.listPaths(target);
      //generate a table and dump the info
      List<String> headingList = new ArrayList<String>();
      headingList.add("Name");
      headingList.add("Type");
      headingList.add("Size");
      if (showUsage) {
        headingList.add("Space Consumed");
      }
      headingList.add("Replication");
      headingList.add("Block Size");
      headingList.add("Modification Time");
      headingList.add("Permission");
      headingList.add("Owner");
      headingList.add("Group" );
      String[] headings = headingList.toArray(new String[headingList.size()]);
      out.print("<h3>Contents of directory ");
      JspHelper.printPathWithLinks(dir, out, namenodeInfoPort, nnAddr);
      out.print("</h3><hr>");
      JspHelper.printGotoForm(out, namenodeInfoPort, dir, nnAddr);
      out.print("<hr>");
	
      File f = new File(dir);
      String parent;
      if ((parent = f.getParent()) != null)
        out.print("<a href=\"" + req.getRequestURL() + "?dir=" + parent +
                  "&namenodeInfoPort=" + namenodeInfoPort +
                  (showUsage ? "&showUsage=1" : "") +
                  JspHelper.getUrlParam(JspHelper.NAMENODE_ADDRESS, nnAddr) +
                  "\">Go to parent directory</a><br>");
	
      if (files == null || files.length == 0) {
        out.print("Empty directory");
      }
      else {
        jspHelper.addTableHeader(out);
        int row=0;
        jspHelper.addTableRow(out, headings, row++);
        String cols [] = new String[headings.length];
        for (int i = 0; i < files.length; i++) {
          int colIdx = 0;
          //Get the location of the first block of the file
          if (files[i].getPath().toString().endsWith(".crc")) continue;
          String datanodeUrl = req.getRequestURL()+"?dir="+
              URLEncoder.encode(files[i].getPath().toString(), "UTF-8") + 
              "&namenodeInfoPort=" + namenodeInfoPort +
              (showUsage ? "&showUsage=1" : "") +
              JspHelper.getUrlParam(JspHelper.NAMENODE_ADDRESS, nnAddr);
          cols[colIdx++] = "<a href=\""+datanodeUrl+"\">"+files[i].getPath().getName()+"</a>";
          if (!files[i].isDir()) {
            cols[colIdx++] = "file";
            cols[colIdx++] = StringUtils.byteDesc(files[i].getLen());
            if (showUsage) {
              cols[colIdx++] = StringUtils.byteDesc(files[i].getLen() * files[i].getReplication());
            }
            cols[colIdx++] = Short.toString(files[i].getReplication());
            cols[colIdx++] = StringUtils.byteDesc(files[i].getBlockSize());
          }
          else {
            cols[colIdx++] = "dir";
            if (showUsage) {
              ContentSummary cs = dfs.getContentSummary(files[i].getPath().toUri().getPath());
              cols[colIdx++] = StringUtils.byteDesc(cs.getLength());
              cols[colIdx++] = StringUtils.byteDesc(cs.getSpaceConsumed());
            } else {
              cols[colIdx++] = "";
            }
            cols[colIdx++] = "";
            cols[colIdx++] = "";
          }
          cols[colIdx++] = FsShell.dateForm.format(new Date((files[i].getModificationTime())));
          cols[colIdx++] = files[i].getPermission().toString();
          cols[colIdx++] = files[i].getOwner();
          cols[colIdx++] = files[i].getGroup();
          jspHelper.addTableRow(out, cols, row++);
        }
        jspHelper.addTableFooter(out);
      }
    } 
    String namenodeHost = namenodeAddress.getAddress().getHostAddress();
    out.print("<br><a href=\"http://" + 
              NetUtils.toIpPort(namenodeHost, namenodeInfoPort) + "/dfshealth.jsp\">Go back to DFS home</a>");
    dfs.close();
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
      out.write("\n\n<html>\n<head>\n<style type=text/css>\n<!--\nbody \n  {\n  font-face:sanserif;\n  }\n-->\n</style>\n");
JspHelper.createTitle(out, request, request.getParameter("dir")); 
      out.write("\n</head>\n\n<body onload=\"document.goto.dir.focus()\">\n");
 
  try {
    generateDirectoryStructure(out,request,response);
  }
  catch(IOException ioe) {
    String msg = ioe.getLocalizedMessage();
    int i = msg.indexOf("\n");
    if (i >= 0) {
      msg = msg.substring(0, i);
    }
    out.print("<h3>" + msg + "</h3>");
  }

      out.write("\n<hr>\n\n<h2>Local logs</h2>\n<a href=\"/logs/\">Log</a> directory\n\n");

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
