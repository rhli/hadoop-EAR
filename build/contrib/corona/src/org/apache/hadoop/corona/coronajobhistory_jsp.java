package org.apache.hadoop.corona;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.fs.*;
import javax.servlet.jsp.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.JobHistory.*;

public final class coronajobhistory_jsp extends org.apache.jasper.runtime.HttpJspBase
    implements org.apache.jasper.runtime.JspSourceDependent {

private void printJob(String trackerHostName, String trackerid,
			String jobId, String jobName, String user, Path logFile,
			JspWriter out) throws IOException {
		out.print("<tr>");
		out.print("<td>" + trackerHostName + "</td>");
		out.print("<td>" + new Date(Long.parseLong(trackerid)) + "</td>");
		out.print("<td>" + "<a href=\"coronajobdetailshistory.jsp?jobid=" + jobId
				+ "&logFile=" + logFile.toString() + "\">" + jobId
				+ "</a></td>");
		out.print("<td>" + jobName + "</td>");
		out.print("<td>" + user + "</td>");
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

	ProxyJobTracker tracker = (ProxyJobTracker) application
			.getAttribute("proxy.job.tracker");
	String trackerName = StringUtils.simpleHostname(tracker
			.getProxyJobTrackerMachine());

      out.write("\n<html>\n<head>\n<script type=\"text/JavaScript\">\n<!--\nfunction showUserHistory(search)\n{\nvar url\nif (search == null || \"\".equals(search)) {\n  url=\"jobhistory.jsp\";\n} else {\n  url=\"jobhistory.jsp?pageno=1&search=\" + search;\n}\nwindow.location.href = url;\n}\n//-->\n</script>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<title>");
      out.print(trackerName);
      out.write(" Hadoop Map/Reduce History Viewer</title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n</head>\n<body>\n<h1> ");
      out.print(trackerName);
      out.write(" Corona Map/Reduce \n     <a href=\"coronajobhistory.jsp\">History Viewer</a></h1>\n<hr>\n");

	final String search = (request.getParameter("search") == null) ? ""
			: request.getParameter("search");

	String parts[] = search.split(":");

	final String user = (parts.length >= 1) ? parts[0].toLowerCase()
			: "";
	final String jobname = (parts.length >= 2) ? parts[1].toLowerCase()
			: "";
	PathFilter jobLogFileFilter = new PathFilter() {
		private boolean matchUser(String fileName) {
			// return true if 
			//  - user is not specified
			//  - user matches
			return "".equals(user)
					|| user.equals(fileName.split("_")[5]);
		}

		private boolean matchJobName(String fileName) {
			// return true if 
			//  - jobname is not specified
			//  - jobname contains the keyword
			return "".equals(jobname)
					|| fileName.split("_")[6].toLowerCase().contains(
							jobname);
		}

		private boolean matchFormat(String fileName) {
			// return true if 
			//  - the filename can be parse correctly
			return fileName.split("_").length >= 7;
		}

		public boolean accept(Path path) {
			return !(path.getName().endsWith(".xml"))
					&& matchFormat(path.getName())
					&& matchUser(path.getName())
					&& matchJobName(path.getName());
		}
	};

	FileSystem fs = (FileSystem) application.getAttribute("fileSys");
	String historyLogDir = (String) application
			.getAttribute("historyLogDir");
	if (fs == null) {
		out.println("Null file system. May be namenode is in safemode!");
		return;
	}
	Path[] jobFiles = FileUtil.stat2Paths(fs.listStatus(new Path(
			historyLogDir), jobLogFileFilter));

    // get the pageno
    int pageno = request.getParameter("pageno") == null
                ? 1
                : Integer.parseInt(request.getParameter("pageno"));

    // get the total number of files to display
    int size = 100;

    // if show-all is requested or jobfiles < size(100)
    if (pageno == -1 || size > jobFiles.length) {
      size = jobFiles.length;
    }

    if (pageno == -1) { // special case 'show all'
      pageno = 1;
    }

    int maxPageNo = (int)Math.ceil((float)jobFiles.length / size);

    // check and fix pageno
    if (pageno < 1 || pageno > maxPageNo) {
      out.println("Invalid page index");
      return ;
    }

    int length = size ; // determine the length of job history files to be displayed
    if (pageno == maxPageNo) {
      // find the number of files to be shown on the last page
      int startOnLast = ((pageno - 1) * size) + 1;
      length = jobFiles.length - startOnLast + 1;
    }

    // Display the search box
    out.println("<form name=search><b> Filter (username:jobname) </b>"); // heading
    out.println("<input type=text name=search size=\"20\" value=\"" + search + "\">"); // search box
    out.println("<input type=submit value=\"Filter!\" onClick=\"showUserHistory(document.getElementById('search').value)\"></form>");
    out.println("<span class=\"small\">Example: 'smith' will display jobs either submitted by user 'smith'. 'smith:sort' will display jobs from user 'smith' having 'sort' keyword in the jobname.</span>"); // example
    out.println("<hr>");

    //Show the status
    int start = (pageno - 1) * size + 1;
	// sort the files on creation time.
	Arrays.sort(jobFiles, new Comparator<Path>() {
		public int compare(Path p1, Path p2) {
			String dp1 = null;
			String dp2 = null;

			try {
				dp1 = JobHistory.JobInfo.decodeJobHistoryFileName(p1
						.getName());
				dp2 = JobHistory.JobInfo.decodeJobHistoryFileName(p2
						.getName());
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}

			String[] split1 = dp1.split("_");
			String[] split2 = dp2.split("_");

			// compare job tracker start time
			int res = new Date(Long.parseLong(split1[1]))
					.compareTo(new Date(Long.parseLong(split2[1])));
			if (res == 0) {
				res = new Date(Long.parseLong(split1[3]))
						.compareTo(new Date(Long.parseLong(split2[3])));
			}
			if (res == 0) {
				Long l1 = Long.parseLong(split1[4]);
				res = l1.compareTo(Long.parseLong(split2[4]));
			}
			return res;
		}
	});
	out.print("<table align=center border=2 cellpadding=\"5\" cellspacing=\"2\">");
	out.print("<tr>");
	out.print("<td>Job tracker Host Name</td>"
			+ "<td>Job tracker Start time</td>"
			+ "<td>Job Id</td><td>Name</td><td>User</td>");
	out.print("</tr>");

	Set<String> displayedJobs = new HashSet<String>();
	for (int i = start - 1; i < start + length - 1; ++i) {
		Path jobFile = jobFiles[i];

		String decodedJobFileName = JobHistory.JobInfo
				.decodeJobHistoryFileName(jobFile.getName());

		String[] jobDetails = decodedJobFileName.split("_");
		String trackerHostName = jobDetails[0];
		String trackerStartTime = jobDetails[1];
		String jobId = jobDetails[2] + "_" + jobDetails[3] + "_"
				+ jobDetails[4];
		String userName = jobDetails[5];
		String jobName = jobDetails[6];

		// Check if the job is already displayed. There can be multiple job 
		// history files for jobs that have restarted
		if (displayedJobs.contains(jobId)) {
			continue;
		} else {
			displayedJobs.add(jobId);
		}

		// Encode the logfile name again to cancel the decoding done by the browser
		String encodedJobFileName = JobHistory.JobInfo
				.encodeJobHistoryFileName(jobFile.getName());

      out.write("\n<center>\n");

	printJob(trackerHostName, trackerStartTime, jobId, jobName,
				userName, new Path(jobFile.getParent(),
						encodedJobFileName), out);

      out.write("\n</center> \n");

 	} // end while trackers 
 	out.print("</table>");
 
      out.write('\n');
      out.write(" \n</body></html>\n");
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
