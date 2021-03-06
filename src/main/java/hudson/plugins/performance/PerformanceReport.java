package hudson.plugins.performance;

import hudson.model.AbstractBuild;

import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.SAXException;

/**
 * Represents a single performance report, which consists of multiple
 * {@link UriReport}s for different URLs that was tested.
 * 
 * This object belongs under {@link PerformanceReportMap}.
 */
public class PerformanceReport extends AbstractReport implements Serializable,
		Comparable<PerformanceReport> {

	private static final long serialVersionUID = 675698410989941826L;

	private transient PerformanceBuildAction buildAction;

	private String reportFileName = null;

	/**
	 * {@link UriReport}s keyed by their {@link UriReport#getStaplerUri()}.
	 */
	private final Map<String, UriReport> uriReportMap = new LinkedHashMap<String, UriReport>();

	private PerformanceReport lastBuildReport;

	/**
	 * A lazy cache of all duration values of all HTTP samples in all UriReports, ordered by duration.
	 */
	private transient List<Long> durationsSortedBySize = null;

	/**
	 * A lazy cache of all UriReports, reverse-ordered.
	 */
	private transient List<UriReport> uriReportsOrdered = null;

	/**
	 * The amount of http samples that are not successful.
	 */
	private int nbError = 0;

	/**
	 * The sum of summarizerErrors values from all samples;
	 */
	private float summarizerErrors = 0;

	/**
	 * The amount of samples in all uriReports combined.
	 */
	private int size;

	/**
	 * The duration of all samples combined, in milliseconds.
	 */
	private long totalDuration = 0;

	/**
	 * The size of all samples combined, in kilobytes.
	 */
	private double totalSizeInKB = 0;

	/**
	 * The longest duration from all samples, or Long.MIN_VALUE when no samples where processed.
	 */
	private long max = Long.MIN_VALUE;

	/**
	 * The shortest duration from all samples, or Long.MAX_VALUE when no samples where processed.
	 */
	private long min = Long.MAX_VALUE;

	
	public static String asStaplerURI(String uri) {
		return uri.replace("http:", "").replaceAll("/", "_");
	}

	public void addSample(HttpSample pHttpSample) throws SAXException {
		String uri = pHttpSample.getUri();
		if (uri == null) {
			buildAction
					.getHudsonConsoleWriter()
					.println("label cannot be empty, please ensure your jmx file specifies "
									+ "name properly for each http sample: skipping sample");
			return;
		}
		String staplerUri = PerformanceReport.asStaplerURI(uri);
		synchronized (uriReportMap) {
			UriReport uriReport = uriReportMap.get(staplerUri);
			if (uriReport == null) {
				uriReport = new UriReport(staplerUri, uri);
				uriReportMap.put(staplerUri, uriReport);
			}
			uriReport.addHttpSample(pHttpSample);

			// reset the lazy loaded caches.
			durationsSortedBySize = null;
			uriReportsOrdered = null;
		}

		if (!pHttpSample.isSuccessful()) {
			nbError++;
		}
		summarizerErrors += pHttpSample.getSummarizerErrors();
		size++;
		totalDuration += pHttpSample.getDuration();
		totalSizeInKB += pHttpSample.getSizeInKb();
		max = Math.max(pHttpSample.getDuration(), max);
		min = Math.min(pHttpSample.getDuration(), min);
	}

	public int compareTo(PerformanceReport jmReport) {
		if (this == jmReport) {
			return 0;
		}
		return getReportFileName().compareTo(jmReport.getReportFileName());
	}

	public int countErrors() {
		return nbError;
	}

	public double errorPercent() {
		if (ifSummarizerParserUsed(reportFileName)) {
			if (uriReportMap.size() == 0) return 0;
			return summarizerErrors / uriReportMap.size();
		} else {
			return size() == 0 ? 0 : ((double) countErrors()) / size() * 100;
		}
	}

	public long getAverage() {
		if (size == 0) {
			return 0;
		}

		return totalDuration / size;
	}

	public double getAverageSizeInKb() {
		if (size == 0) {
			return 0;
		}
		return roundTwoDecimals(totalSizeInKB / size);
	}

	private long getDurationAt(double percentage) {
		if (percentage < 0 || percentage > 1) {
			throw new IllegalArgumentException("Argument 'percentage' must be a value between 0 and 1 (inclusive)");
		}

		if (size == 0) {
			return 0;
		}

		synchronized (uriReportMap) {
			if (durationsSortedBySize == null) {
				durationsSortedBySize = new ArrayList<Long>();
				for (UriReport currentReport : uriReportMap.values()) {
					durationsSortedBySize.addAll(currentReport.getDurations());
				}
				Collections.sort(durationsSortedBySize);
			}
			return durationsSortedBySize.get((int) (durationsSortedBySize.size() * percentage));
		}
	}

	public long get90Line() {
		return getDurationAt(.9);
	}

	public long getMedian() {
		return getDurationAt(.5);
	}

	public String getHttpCode() {
		return "";
	}

	public AbstractBuild<?, ?> getBuild() {
		return buildAction.getBuild();
	}

	PerformanceBuildAction getBuildAction() {
		return buildAction;
	}

	public String getDisplayName() {
		return Messages.Report_DisplayName();
	}

	public UriReport getDynamic(String token) throws IOException {
		return getUriReportMap().get(token);
	}

	public long getMax() {
		return max;
	}

	public double getTotalTrafficInKb() {
		return roundTwoDecimals(totalSizeInKB);
	}

	public long getMin() {
		return min;
	}

	public String getReportFileName() {
		return reportFileName;
	}

	public List<UriReport> getUriListOrdered() {
		synchronized (uriReportMap) {
			if (uriReportsOrdered == null) {
				uriReportsOrdered = new ArrayList<UriReport>(uriReportMap.values());
				Collections.sort(uriReportsOrdered, Collections.reverseOrder());
			}
			return uriReportsOrdered;
		}
	}

	public Map<String, UriReport> getUriReportMap() {
		return uriReportMap;
	}

	void setBuildAction(PerformanceBuildAction buildAction) {
		this.buildAction = buildAction;
	}

	public void setReportFileName(String reportFileName) {
		this.reportFileName = reportFileName;
	}

	public int size() {
		return size;
	}

	public void setLastBuildReport(PerformanceReport lastBuildReport) {
		Map<String, UriReport> lastBuildUriReportMap = lastBuildReport
				.getUriReportMap();
		for (Map.Entry<String, UriReport> item : uriReportMap.entrySet()) {
			UriReport lastBuildUri = lastBuildUriReportMap.get(item.getKey());
			if (lastBuildUri != null) {
				item.getValue().addLastBuildUriReport(lastBuildUri);
			}
		}
		this.lastBuildReport = lastBuildReport;
	}

	public long getAverageDiff() {
		if (lastBuildReport == null) {
			return 0;
		}
		return getAverage() - lastBuildReport.getAverage();
	}

	public long getMedianDiff() {
		if (lastBuildReport == null) {
			return 0;
		}
		return getMedian() - lastBuildReport.getMedian();
	}

	public double getErrorPercentDiff() {
		if (lastBuildReport == null) {
			return 0;
		}
		return errorPercent() - lastBuildReport.errorPercent();
	}

	public String getLastBuildHttpCodeIfChanged() {
		return "";
	}

	public int getSizeDiff() {
		if (lastBuildReport == null) {
			return 0;
		}
		return size() - lastBuildReport.size();
	}

	/**
	 * Check if the filename of the file being parsed is being parsed by a
	 * summarized parser (JMeterSummarizer).
	 * 
	 * @param filename
	 *            name of the file being parsed
	 * @return boolean indicating usage of summarized parser
	 */
	public boolean ifSummarizerParserUsed(String filename) {
		List<PerformanceReportParser> list = buildAction.getBuild().getProject()
				.getPublishersList().get(PerformancePublisher.class).getParsers();

		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).getDescriptor().getDisplayName()
					.equals("JmeterSummarizer")) {
				String fileExt = list.get(i).glob;
				String parts[] = fileExt.split("\\s*[;:,]+\\s*");
				for (String path : parts) {
					if (filename.endsWith(path.substring(5))) {
						return true;
					}
				}
			} else if (list.get(i).getDescriptor().getDisplayName()
					.equals("Iago")) {
				return true;
			}

		}
		return false;
	}

	private double roundTwoDecimals(double d) {
		synchronized (twoDForm) {
			return Double.valueOf(twoDForm.format(d));
		}
	}
}
