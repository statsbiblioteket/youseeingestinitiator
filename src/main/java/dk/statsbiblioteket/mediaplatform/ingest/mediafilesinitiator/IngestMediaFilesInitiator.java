package dk.statsbiblioteket.mediaplatform.ingest.mediafilesinitiator;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.LocalTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import dk.statsbiblioteket.mediaplatform.ingest.model.ChannelArchiveRequest;
import dk.statsbiblioteket.mediaplatform.ingest.model.service.ChannelArchiveRequestServiceIF;
import dk.statsbiblioteket.mediaplatform.ingest.model.service.ServiceException;
import dk.statsbiblioteket.mediaplatform.ingest.model.service.YouSeeChannelMappingServiceIF;

/**
 * Formålet med denne klasse er at starte et workflow til ingest af nye mediefiler i Medieplatformen.
 * I praksis betyder det at komponenterne BitMag, DOMS og DigiTV bliver beriget med data og metadata
 * om nye filer.
 * 
 * Klassen repræsenterer det første skridt i et workflow, der downloader, karakteriserer og 
 * kvalitetstjekker filerne inden de ingestes i slutsystemerne.
 * 
 * @author henningbottger
 *
 */
public class IngestMediaFilesInitiator {

    private static final Logger log = Logger.getLogger(IngestMediaFilesInitiator.class);;
    private static final String YOUSEE_RECORDINGS_DAYS_TO_KEEP_KEY = "yousee.recordings.days.to.keep";
    private static final DateTimeFormatter outputDateFormatter = DateTimeFormat.forPattern("yyyyMMddHHmmss");;

    private Properties properties;
    private ChannelArchiveRequestServiceIF channelArchiveRequestService;
    private YouSeeChannelMappingServiceIF youSeeChannelMappingService;
    private OutputStream outputStream;

    private IngestMediaFilesInitiator() {
        super();
    }
    
    public IngestMediaFilesInitiator(Properties properties, ChannelArchiveRequestServiceIF channelArchiveRequestDAO, YouSeeChannelMappingServiceIF youSeeChannelMappingService, OutputStream outputStream) {
        this();
        this.properties = properties;
        this.channelArchiveRequestService = channelArchiveRequestDAO;
        this.youSeeChannelMappingService = youSeeChannelMappingService;
        this.outputStream = outputStream;
    }

    /**
     * Input til initiatoren er en dato. Initiatoren gennegår derpå følgende skridt:
     * 
     * <ol>
     *   <li>Udled periode som vi ønsker at downloade filer for, eg. nu og 28 dage tilbage</li>
     *   
     *   <li>Hent planlagt optageperioder fra ChannelArchiveRequestService</li>
     *   
     *   <li>Udled hvilke filer der skal downloades ud fra planlagte optageperioder og den periode vi 
     *          ønsker at downloade filer fra</li>
     *          
     *   <li>Filtrer filer fra som vi allerede har ingested i systemet</li>
     *   
     *   <li>Output ingest job for hver fil der ønskes ingested til stdout</li>
     *   
     * </ol>
     * 
     * 
     * @param dateOfIngest Last date in download period.
     * @throws NullPointerException if dateOfIngest is null
     */
    public void initiate(DateTime dateOfIngest) {
        log.info("Initiated ingest based on date: " + dateOfIngest);
        // Infer period to ingest
        int daysYouSeeKeepsRecordings = Integer.parseInt(properties.getProperty(YOUSEE_RECORDINGS_DAYS_TO_KEEP_KEY));
        DateTime toDate = dateOfIngest;
        DateTime fromDate = dateOfIngest.minusDays(daysYouSeeKeepsRecordings-1); // dateOfIngest counts as one day
        List<ChannelArchiveRequest> caRequests = channelArchiveRequestService.getValidRequests(fromDate.toDate(), toDate.toDate());
        List<MediaFileIngestParameters> fullFileList = inferFilesToIngest(caRequests, fromDate, toDate);
        List<MediaFileIngestParameters> filteredFileList = filter(new ArrayList<MediaFileIngestParameters>(fullFileList));
        outputResult(filteredFileList, outputStream);
        log.info("Done initiating ingest based on date: " + dateOfIngest);
    }

    protected List<MediaFileIngestParameters> inferFilesToIngest(List<ChannelArchiveRequest> caRequests, DateTime fromDate, DateTime toDate) {
        log.debug("Infering files to ingest. Request: " + caRequests + ", fromDate: " + fromDate + ", toDate: " + toDate);
        Set<MediaFileIngestParameters> filesToIngest = new HashSet<MediaFileIngestParameters>();
        DateTime dayToCheck = fromDate;
        while (dayToCheck.isBefore(toDate) || dayToCheck.equals(toDate)) {
            for (ChannelArchiveRequest car : caRequests) {
                filesToIngest.addAll(inferFilesToIngest(car, dayToCheck));
            }
            dayToCheck = dayToCheck.plusDays(1);
        }
        List<MediaFileIngestParameters> fileList = new ArrayList<MediaFileIngestParameters>(filesToIngest);
        Collections.sort(fileList);
        return fileList;
    }

    /**
     * Given request and a date, the method finds the hour intervals to download. The
     * 1 hour intervals are always in whole hours, ie. minutes are 0. In order to 
     * fulfill the request, the download intervals may be longer than the specific
     * requests. 
     * 
     *  - Request Channel:DR1 From:14:30 To:15:30
     * 
     *  Corresponding download intervals
     *  
     *  - Interval 1: Channel:DR1 From:14:00 To:15:00
     *  - Interval 2: Channel:DR1 From:15:00 To:16:00
     * 
     * @param caRequest
     * @param dayToCheck
     * @return
     */
    protected Set<MediaFileIngestParameters> inferFilesToIngest(ChannelArchiveRequest caRequest, DateTime dayToCheck) {
        Set<MediaFileIngestParameters> filesToIngest = new HashSet<MediaFileIngestParameters>();
        try {
            if (isChannelArchiveRequestActive(caRequest, dayToCheck)) {
                String sbChannelID = caRequest.getsBChannelId();
                LocalTime localTimeFrom = new LocalTime(caRequest.getFromTime().getTime());
                LocalTime localTimeTo = new LocalTime(caRequest.getToTime().getTime());
                int fromHour = localTimeFrom.getHourOfDay();
                int toHour = localTimeTo.getHourOfDay();
                if (localTimeTo.getMinuteOfHour() != 0) {
                    toHour++;
                }
                int hour = fromHour;
                while (hour < toHour) {
                    DateTime startDate = new DateTime(dayToCheck.getYear(), dayToCheck.getMonthOfYear(), dayToCheck.getDayOfMonth(), hour, 0);
                    DateTime endDate = startDate.plusHours(1);
                    String youseeChannelID = youSeeChannelMappingService.getUniqueMappingFromSbChannelId(sbChannelID, startDate.toDate()).getYouSeeChannelId();
                    String youseeFilename = 
                            youseeChannelID + "_"
                            + outputDateFormatter.print(startDate) + "_"
                            + outputDateFormatter.print(endDate) + ".mux";
                    filesToIngest.add(new MediaFileIngestParameters(youseeFilename, sbChannelID, youseeChannelID, startDate, endDate));
                    hour++;
                }
            }
        } catch (ServiceException e) {
            throw new RuntimeException("An unexpected error occured.", e);
        }
        return filesToIngest;
    }

    protected boolean isChannelArchiveRequestActive(ChannelArchiveRequest caRequest, DateTime dayToCheck) {
        boolean caRequestActive = false;
        DateTime caRequestFromDate = new DateTime(caRequest.getFromDate()); 
        DateTime caRequestToDate = new DateTime(caRequest.getToDate()); 
        boolean caRequestPeriodValid = (caRequestFromDate.equals(dayToCheck) || caRequestFromDate.isBefore(dayToCheck)) && 
                (caRequestToDate.equals(dayToCheck) || caRequestToDate.isAfter(dayToCheck));
        if (caRequestPeriodValid) {
            switch (caRequest.getWeekdayCoverage()) {
            case DAILY:
                caRequestActive = true;
                break;

            case MONDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.MONDAY) {
                    caRequestActive = true;
                }
                break;

            case TUESDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.TUESDAY) {
                    caRequestActive = true;
                }
                break;

            case WEDNESDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.WEDNESDAY) {
                    caRequestActive = true;
                }
                break;

            case THURSDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.THURSDAY) {
                    caRequestActive = true;
                }
                break;

            case FRIDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.FRIDAY) {
                    caRequestActive = true;
                }
                break;

            case SATURDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.SATURDAY) {
                    caRequestActive = true;
                }
                break;

            case SUNDAY:
                if (dayToCheck.getDayOfWeek() == DateTimeConstants.SUNDAY) {
                    caRequestActive = true;
                }
                break;

            case MONDAY_TO_THURSDAY:
                if ((dayToCheck.getDayOfWeek() == DateTimeConstants.MONDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.TUESDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.WEDNESDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.THURSDAY)) {
                    caRequestActive = true;
                }
                break;

            case MONDAY_TO_FRIDAY:
                if ((dayToCheck.getDayOfWeek() == DateTimeConstants.MONDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.TUESDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.WEDNESDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.THURSDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.FRIDAY)) {
                    caRequestActive = true;
                }
                break;

            case SATURDAY_AND_SUNDAY:
                if ((dayToCheck.getDayOfWeek() == DateTimeConstants.SATURDAY) ||
                        (dayToCheck.getDayOfWeek() == DateTimeConstants.SUNDAY)) {
                    caRequestActive = true;
                }
                break;

            default:
                throw new RuntimeException("Unknown Weekday enum: " + caRequest.getWeekdayCoverage());
            }
        }
        return caRequestActive;
    }

    protected List<MediaFileIngestParameters> filter(List<MediaFileIngestParameters> unFilteredOutputList) {
        List<MediaFileIngestParameters> filteredList = new ArrayList<MediaFileIngestParameters>();
        log.warn("No filtering is implemented.");
        // TODO: Do real stuff
        return filteredList;
    }

    /**
     * Converts ingest parameters to JSON format and outputs to the given PrintWriter.
     *
     * Example of output:
     * ---
     * {
     *     "downloads":[
     *         {
     *             "fileID" : "DR HD_20120915_100000_20120915_110000.mux",
     *             "startTime" : "20120915100000",
     *             "endTime" : "20120915110000",
     *             "youseeChannelID" : "DR HD",
     *             "sbChannelID" : "drhd"
     *         },
     *         {
     *             "fileID" : "DR HD_20120915_110000_20120915_120000.mux",
     *             "startTime" : "20120915110000",
     *             "endTime" : "20120915120000",
     *             "youseeChannelID" : "DR HD",
     *             "sbChannelID" : "drhd"
     *         }
     *     ]
     * }
     *---
     * @param outputList
     * @param outputStream
     */
    protected void outputResult(List<MediaFileIngestParameters> outputList, OutputStream outputStream) {
        boolean firstEntry = true;
        String output = " {\n"
                + "     \"downloads\":[";
        for (MediaFileIngestParameters mediaFileIngestParameters : outputList) {
            String params = "\n"
                    + "         {\n"
                    + "            \"fileID\" : \"" +          mediaFileIngestParameters.youseeFileName + "\",\n"
                    + "            \"startTime\" : \"" +       outputDateFormatter.print(mediaFileIngestParameters.getStartDate()) + "\",\n"
                    + "            \"endTime\" : \"" +         outputDateFormatter.print(mediaFileIngestParameters.getEndDate()) + "\",\n"
                    + "            \"youseeChannelID\" : \"" + mediaFileIngestParameters.getChannelIDYouSee() + "\",\n"
                    + "            \"sbChannelID\" : \"" +     mediaFileIngestParameters.getChannelIDSB() + "\"\n"
                    + "         }";
            if (firstEntry) {
                output += params;
                firstEntry = false;
            } else {
                output += "," + params;
            }
        }
        output += "\n"
                + "     ]\n"
                + " }\n";
        try {
            log.debug("Writing output: " + output);
            outputStream.write(output.getBytes());
            log.debug("Closing output.");
            outputStream.close();
            log.debug("Closed output.");
        } catch (IOException e) {
            throw new RuntimeException("Unable to output to: " + outputStream, e);
        }
    }
}
