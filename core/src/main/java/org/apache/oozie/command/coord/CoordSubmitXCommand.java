/**
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. See accompanying LICENSE file.
 */
package org.apache.oozie.command.coord;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Validator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.oozie.CoordinatorJobBean;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.client.CoordinatorJob;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.CoordinatorJob.Execution;
import org.apache.oozie.command.CommandException;
import org.apache.oozie.command.bundle.BundleStatusUpdateXCommand;
import org.apache.oozie.coord.CoordELEvaluator;
import org.apache.oozie.coord.CoordELFunctions;
import org.apache.oozie.coord.CoordinatorJobException;
import org.apache.oozie.coord.TimeUnit;
import org.apache.oozie.executor.jpa.CoordJobInsertJPAExecutor;
import org.apache.oozie.executor.jpa.JPAExecutorException;
import org.apache.oozie.service.DagXLogInfoService;
import org.apache.oozie.service.HadoopAccessorException;
import org.apache.oozie.service.HadoopAccessorService;
import org.apache.oozie.service.JPAService;
import org.apache.oozie.service.SchemaService;
import org.apache.oozie.service.Service;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.UUIDService;
import org.apache.oozie.service.WorkflowAppService;
import org.apache.oozie.service.SchemaService.SchemaName;
import org.apache.oozie.service.UUIDService.ApplicationType;
import org.apache.oozie.util.DateUtils;
import org.apache.oozie.util.ELEvaluator;
import org.apache.oozie.util.IOUtils;
import org.apache.oozie.util.InstrumentUtils;
import org.apache.oozie.util.LogUtils;
import org.apache.oozie.util.ParamChecker;
import org.apache.oozie.util.PropertiesUtils;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XLog;
import org.apache.oozie.util.XmlUtils;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.xml.sax.SAXException;

/**
 * This class provides the functionalities to resolve a coordinator job XML and
 * write the job information into a DB table.
 * <p/>
 * Specifically it performs the following functions: 1. Resolve all the
 * variables or properties using job configurations. 2. Insert all datasets
 * definition as part of the <data-in> and <data-out> tags. 3. Validate the XML
 * at runtime.
 */
public class CoordSubmitXCommand extends CoordinatorXCommand<String> {

    private final Configuration conf;
    private final String authToken;
    private final String bundleId;
    private final String coordName;
    private boolean dryrun;
    private JPAService jpaService = null;

    public static final String CONFIG_DEFAULT = "coord-config-default.xml";
    public static final String COORDINATOR_XML_FILE = "coordinator.xml";

    private static final Set<String> DISALLOWED_USER_PROPERTIES = new HashSet<String>();
    private static final Set<String> DISALLOWED_DEFAULT_PROPERTIES = new HashSet<String>();
    /**
     * Default timeout for normal jobs, in minutes, after which coordinator
     * input check will timeout
     */
    public static final String CONF_DEFAULT_TIMEOUT_NORMAL = Service.CONF_PREFIX + "coord.normal.default.timeout";

    private final XLog log = XLog.getLog(getClass());
    private ELEvaluator evalFreq = null;
    private ELEvaluator evalNofuncs = null;
    private ELEvaluator evalData = null;
    private ELEvaluator evalInst = null;
    private ELEvaluator evalSla = null;

    static {
        String[] badUserProps = { PropertiesUtils.YEAR, PropertiesUtils.MONTH, PropertiesUtils.DAY,
                PropertiesUtils.HOUR, PropertiesUtils.MINUTE, PropertiesUtils.DAYS, PropertiesUtils.HOURS,
                PropertiesUtils.MINUTES, PropertiesUtils.KB, PropertiesUtils.MB, PropertiesUtils.GB,
                PropertiesUtils.TB, PropertiesUtils.PB, PropertiesUtils.RECORDS, PropertiesUtils.MAP_IN,
                PropertiesUtils.MAP_OUT, PropertiesUtils.REDUCE_IN, PropertiesUtils.REDUCE_OUT, PropertiesUtils.GROUPS };
        PropertiesUtils.createPropertySet(badUserProps, DISALLOWED_USER_PROPERTIES);

        String[] badDefaultProps = { PropertiesUtils.HADOOP_USER, PropertiesUtils.HADOOP_UGI,
                WorkflowAppService.HADOOP_JT_KERBEROS_NAME, WorkflowAppService.HADOOP_NN_KERBEROS_NAME };
        PropertiesUtils.createPropertySet(badUserProps, DISALLOWED_DEFAULT_PROPERTIES);
        PropertiesUtils.createPropertySet(badDefaultProps, DISALLOWED_DEFAULT_PROPERTIES);
    }

    /**
     * Constructor to create the Coordinator Submit Command.
     *
     * @param conf : Configuration for Coordinator job
     * @param authToken : To be used for authentication
     */
    public CoordSubmitXCommand(Configuration conf, String authToken) {
        super("coord_submit", "coord_submit", 1);
        this.conf = ParamChecker.notNull(conf, "conf");
        this.authToken = ParamChecker.notEmpty(authToken, "authToken");
        this.bundleId = null;
        this.coordName = null;
    }

    /**
     * Constructor to create the Coordinator Submit Command by bundle job.
     *
     * @param conf : Configuration for Coordinator job
     * @param authToken : To be used for authentication
     * @param bundleId : bundle id
     * @param coordName : coord name
     */
    public CoordSubmitXCommand(Configuration conf, String authToken, String bundleId, String coordName) {
        super("coord_submit", "coord_submit", 1);
        this.conf = ParamChecker.notNull(conf, "conf");
        this.authToken = ParamChecker.notEmpty(authToken, "authToken");
        this.bundleId = ParamChecker.notEmpty(bundleId, "bundleId");
        this.coordName = ParamChecker.notEmpty(coordName, "coordName");
    }

    /**
     * Constructor to create the Coordinator Submit Command.
     *
     * @param dryrun : if dryrun
     * @param conf : Configuration for Coordinator job
     * @param authToken : To be used for authentication
     */
    public CoordSubmitXCommand(boolean dryrun, Configuration conf, String authToken) {
        this(conf, authToken);
        this.dryrun = dryrun;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#execute()
     */
    @Override
    protected String execute() throws CommandException {
        String jobId = null;
        log.info("STARTED Coordinator Submit");
        InstrumentUtils.incrJobCounter(getName(), 1, getInstrumentation());
        CoordinatorJobBean coordJob = new CoordinatorJobBean();
        CoordinatorJob.Status prevStatus = CoordinatorJob.Status.PREP;
        boolean exceptionOccured = false;
        try {
            XLog.Info.get().setParameter(DagXLogInfoService.TOKEN, conf.get(OozieClient.LOG_TOKEN));
            mergeDefaultConfig();

            String appXml = readAndValidateXml();
            coordJob.setOrigJobXml(appXml);
            log.debug("jobXml after initial validation " + XmlUtils.prettyPrint(appXml).toString());
            appXml = XmlUtils.removeComments(appXml);
            initEvaluators();
            Element eJob = basicResolveAndIncludeDS(appXml, conf, coordJob);
            log.debug("jobXml after all validation " + XmlUtils.prettyPrint(eJob).toString());

            jobId = storeToDB(eJob, coordJob);
            // log job info for coordinator jobs
            LogUtils.setLogInfo(coordJob, logInfo);
            prevStatus = CoordinatorJob.Status.PREP;

            if (!dryrun) {
                // submit a command to materialize jobs for the next 1 hour (3600 secs)
                // so we don't wait 10 mins for the Service to run.
                queue(new CoordJobMatLookupXCommand(jobId, 3600), 100);
            }
            else {
                Date startTime = coordJob.getStartTime();
                long startTimeMilli = startTime.getTime();
                long endTimeMilli = startTimeMilli + (3600 * 1000);
                Date jobEndTime = coordJob.getEndTime();
                Date endTime = new Date(endTimeMilli);
                if (endTime.compareTo(jobEndTime) > 0) {
                    endTime = jobEndTime;
                }
                jobId = coordJob.getId();
                log.info("[" + jobId + "]: Update status to PREMATER");
                coordJob.setStatus(CoordinatorJob.Status.PREMATER);
                CoordActionMaterializeCommand coordActionMatCom = new CoordActionMaterializeCommand(jobId, startTime,
                        endTime);
                Configuration jobConf = null;
                try {
                    jobConf = new XConfiguration(new StringReader(coordJob.getConf()));
                }
                catch (IOException e1) {
                    log.warn("Configuration parse error. read from DB :" + coordJob.getConf(), e1);
                }
                String action = coordActionMatCom.materializeJobs(true, coordJob, jobConf, null);
                String output = coordJob.getJobXml() + System.getProperty("line.separator")
                        + "***actions for instance***" + action;
                return output;
            }
        }
        catch (CoordinatorJobException cex) {
            exceptionOccured = true;
            log.warn("ERROR:  ", cex);
            throw new CommandException(cex);
        }
        catch (IllegalArgumentException iex) {
            exceptionOccured = true;
            log.warn("ERROR:  ", iex);
            throw new CommandException(ErrorCode.E1003, iex);
        }
        catch (Exception ex) {
            exceptionOccured = true;
            log.warn("ERROR:  ", ex);
            throw new CommandException(ErrorCode.E0803, ex);
        }
        finally {
            if (exceptionOccured) {
                coordJob.setStatus(CoordinatorJob.Status.FAILED);
            }
            //update bundle action
            if (this.bundleId != null) {
                BundleStatusUpdateXCommand bundleStatusUpdate = new BundleStatusUpdateXCommand(coordJob, prevStatus);
                bundleStatusUpdate.call();
            }
        }
        log.info("ENDED Coordinator Submit jobId=" + jobId);
        return jobId;
    }

    /**
     * Read the application XML and validate against coordinator Schema
     *
     * @return validated coordinator XML
     * @throws CoordinatorJobException thrown if unable to read or validate coordinator xml
     */
    private String readAndValidateXml() throws CoordinatorJobException {
        String appPath = ParamChecker.notEmpty(conf.get(OozieClient.COORDINATOR_APP_PATH),
                OozieClient.COORDINATOR_APP_PATH);
        String coordXml = readDefinition(appPath);
        validateXml(coordXml);
        return coordXml;
    }

    /**
     * Validate against Coordinator XSD file
     *
     * @param xmlContent : Input coordinator xml
     * @throws CoordinatorJobException thrown if unable to validate coordinator xml
     */
    private void validateXml(String xmlContent) throws CoordinatorJobException {
        javax.xml.validation.Schema schema = Services.get().get(SchemaService.class).getSchema(SchemaName.COORDINATOR);
        Validator validator = schema.newValidator();
        try {
            validator.validate(new StreamSource(new StringReader(xmlContent)));
        }
        catch (SAXException ex) {
            log.warn("SAXException :", ex);
            throw new CoordinatorJobException(ErrorCode.E0701, ex.getMessage(), ex);
        }
        catch (IOException ex) {
            log.warn("IOException :", ex);
            throw new CoordinatorJobException(ErrorCode.E0702, ex.getMessage(), ex);
        }
    }

    /**
     * Merge default configuration with user-defined configuration.
     *
     * @throws CommandException thrown if failed to read or merge configurations
     */
    protected void mergeDefaultConfig() throws CommandException {
        Path appPath = new Path(conf.get(OozieClient.COORDINATOR_APP_PATH));
        Path configDefault = new Path(appPath.getParent(), CONFIG_DEFAULT);
        // Configuration fsConfig = CoordUtils.getHadoopConf(conf);
        try {
            String user = ParamChecker.notEmpty(conf.get(OozieClient.USER_NAME), OozieClient.USER_NAME);
            String group = ParamChecker.notEmpty(conf.get(OozieClient.GROUP_NAME), OozieClient.GROUP_NAME);
            FileSystem fs = Services.get().get(HadoopAccessorService.class).createFileSystem(user, group,
                    configDefault.toUri(), new Configuration());
            if (fs.exists(configDefault)) {
                Configuration defaultConf = new XConfiguration(fs.open(configDefault));
                PropertiesUtils.checkDisallowedProperties(defaultConf, DISALLOWED_DEFAULT_PROPERTIES);
                XConfiguration.injectDefaults(defaultConf, conf);
            }
            else {
                log.info("configDefault Doesn't exist " + configDefault);
            }
            PropertiesUtils.checkDisallowedProperties(conf, DISALLOWED_USER_PROPERTIES);
        }
        catch (IOException e) {
            throw new CommandException(ErrorCode.E0702, e.getMessage() + " : Problem reading default config "
                    + configDefault, e);
        }
        catch (HadoopAccessorException e) {
            throw new CommandException(e);
        }
        log.debug("Merged CONF :" + XmlUtils.prettyPrint(conf).toString());
    }

    /**
     * The method resolve all the variables that are defined in configuration.
     * It also include the data set definition from dataset file into XML.
     *
     * @param appXml : Original job XML
     * @param conf : Configuration of the job
     * @param coordJob : Coordinator job bean to be populated.
     * @return Resolved and modified job XML element.
     * @throws CoordinatorJobException thrown if failed to resolve basic entities or include referred datasets
     * @throws Exception thrown if failed to resolve basic entities or include referred datasets
     */
    public Element basicResolveAndIncludeDS(String appXml, Configuration conf, CoordinatorJobBean coordJob)
            throws CoordinatorJobException, Exception {
        Element basicResolvedApp = resolveInitial(conf, appXml, coordJob);
        includeDataSets(basicResolvedApp, conf);
        return basicResolvedApp;
    }

    /**
     * Insert data set into data-in and data-out tags.
     *
     * @param eAppXml : coordinator application XML
     * @param eDatasets : DataSet XML
     */
    @SuppressWarnings("unchecked")
    private void insertDataSet(Element eAppXml, Element eDatasets) {
        // Adding DS definition in the coordinator XML
        Element inputList = eAppXml.getChild("input-events", eAppXml.getNamespace());
        if (inputList != null) {
            for (Element dataIn : (List<Element>) inputList.getChildren("data-in", eAppXml.getNamespace())) {
                Element eDataset = findDataSet(eDatasets, dataIn.getAttributeValue("dataset"));
                dataIn.getContent().add(0, eDataset);
            }
        }
        Element outputList = eAppXml.getChild("output-events", eAppXml.getNamespace());
        if (outputList != null) {
            for (Element dataOut : (List<Element>) outputList.getChildren("data-out", eAppXml.getNamespace())) {
                Element eDataset = findDataSet(eDatasets, dataOut.getAttributeValue("dataset"));
                dataOut.getContent().add(0, eDataset);
            }
        }
    }

    /**
     * Find a specific dataset from a list of Datasets.
     *
     * @param eDatasets : List of data sets
     * @param name : queried data set name
     * @return one Dataset element. otherwise throw Exception
     */
    @SuppressWarnings("unchecked")
    private static Element findDataSet(Element eDatasets, String name) {
        for (Element eDataset : (List<Element>) eDatasets.getChildren("dataset", eDatasets.getNamespace())) {
            if (eDataset.getAttributeValue("name").equals(name)) {
                eDataset = (Element) eDataset.clone();
                eDataset.detach();
                return eDataset;
            }
        }
        throw new RuntimeException("undefined dataset: " + name);
    }

    /**
     * Initialize all the required EL Evaluators.
     */
    protected void initEvaluators() {
        evalFreq = CoordELEvaluator.createELEvaluatorForGroup(conf, "coord-job-submit-freq");
        evalNofuncs = CoordELEvaluator.createELEvaluatorForGroup(conf, "coord-job-submit-nofuncs");
        evalInst = CoordELEvaluator.createELEvaluatorForGroup(conf, "coord-job-submit-instances");
        evalSla = CoordELEvaluator.createELEvaluatorForGroup(conf, "coord-sla-submit");
    }

    /**
     * Resolve basic entities using job Configuration.
     *
     * @param conf :Job configuration
     * @param appXml : Original job XML
     * @param coordJob : Coordinator job bean to be populated.
     * @return Resolved job XML element.
     * @throws CoordinatorJobException thrown if failed to resolve basic entities
     * @throws Exception thrown if failed to resolve basic entities
     */
    @SuppressWarnings("unchecked")
    protected Element resolveInitial(Configuration conf, String appXml, CoordinatorJobBean coordJob)
            throws CoordinatorJobException, Exception {
        Element eAppXml = XmlUtils.parseXml(appXml);
        // job's main attributes
        // frequency
        String val = resolveAttribute("frequency", eAppXml, evalFreq);
        int ival = ParamChecker.checkInteger(val, "frequency");
        ParamChecker.checkGTZero(ival, "frequency");
        coordJob.setFrequency(ival);
        TimeUnit tmp = (evalFreq.getVariable("timeunit") == null) ? TimeUnit.MINUTE : ((TimeUnit) evalFreq
                .getVariable("timeunit"));
        addAnAttribute("freq_timeunit", eAppXml, tmp.toString());
        // TimeUnit
        coordJob.setTimeUnit(CoordinatorJob.Timeunit.valueOf(tmp.toString()));
        // End Of Duration
        tmp = evalFreq.getVariable("endOfDuration") == null ? TimeUnit.NONE : ((TimeUnit) evalFreq
                .getVariable("endOfDuration"));
        addAnAttribute("end_of_duration", eAppXml, tmp.toString());
        // coordJob.setEndOfDuration(tmp) // TODO: Add new attribute in Job bean

        // start time
        val = resolveAttribute("start", eAppXml, evalNofuncs);
        ParamChecker.checkUTC(val, "start");
        coordJob.setStartTime(DateUtils.parseDateUTC(val));
        // end time
        val = resolveAttribute("end", eAppXml, evalNofuncs);
        ParamChecker.checkUTC(val, "end");
        coordJob.setEndTime(DateUtils.parseDateUTC(val));
        // Time zone
        val = resolveAttribute("timezone", eAppXml, evalNofuncs);
        ParamChecker.checkTimeZone(val, "timezone");
        coordJob.setTimeZone(val);

        // controls
        val = resolveTagContents("timeout", eAppXml.getChild("controls", eAppXml.getNamespace()), evalNofuncs);
        if (val == "") {
            val = Services.get().getConf().get(CONF_DEFAULT_TIMEOUT_NORMAL);
        }

        ival = ParamChecker.checkInteger(val, "timeout");
        coordJob.setTimeout(ival);
        val = resolveTagContents("concurrency", eAppXml.getChild("controls", eAppXml.getNamespace()), evalNofuncs);
        if (val == "") {
            val = "-1";
        }
        ival = ParamChecker.checkInteger(val, "concurrency");
        coordJob.setConcurrency(ival);
        val = resolveTagContents("execution", eAppXml.getChild("controls", eAppXml.getNamespace()), evalNofuncs);
        if (val == "") {
            val = Execution.FIFO.toString();
        }
        coordJob.setExecution(Execution.valueOf(val));
        String[] acceptedVals = { Execution.LIFO.toString(), Execution.FIFO.toString(), Execution.LAST_ONLY.toString() };
        ParamChecker.isMember(val, acceptedVals, "execution");

        // datasets
        resolveTagContents("include", eAppXml.getChild("datasets", eAppXml.getNamespace()), evalNofuncs);
        // for each data set
        resolveDataSets(eAppXml);
        HashMap<String, String> dataNameList = new HashMap<String, String>();
        resolveIOEvents(eAppXml, dataNameList);

        resolveTagContents("app-path", eAppXml.getChild("action", eAppXml.getNamespace()).getChild("workflow",
                eAppXml.getNamespace()), evalNofuncs);
        // TODO: If action or workflow tag is missing, NullPointerException will
        // occur
        Element configElem = eAppXml.getChild("action", eAppXml.getNamespace()).getChild("workflow",
                eAppXml.getNamespace()).getChild("configuration", eAppXml.getNamespace());
        evalData = CoordELEvaluator.createELEvaluatorForDataEcho(conf, "coord-job-submit-data", dataNameList);
        if (configElem != null) {
            for (Element propElem : (List<Element>) configElem.getChildren("property", configElem.getNamespace())) {
                resolveTagContents("name", propElem, evalData);
                // Want to check the data-integrity but don't want to modify the
                // XML
                // for properties only
                Element tmpProp = (Element) propElem.clone();
                resolveTagContents("value", tmpProp, evalData);
            }
        }
        resolveSLA(eAppXml, coordJob);
        return eAppXml;
    }

    /**
     * Resolve SLA events
     *
     * @param eAppXml job XML
     * @param coordJob coordinator job bean
     * @throws CommandException thrown if failed to resolve sla events
     */
    private void resolveSLA(Element eAppXml, CoordinatorJobBean coordJob) throws CommandException {
        Element eSla = eAppXml.getChild("action", eAppXml.getNamespace()).getChild("info",
                Namespace.getNamespace(SchemaService.SLA_NAME_SPACE_URI));

        if (eSla != null) {
            String slaXml = XmlUtils.prettyPrint(eSla).toString();
            try {
                // EL evaluation
                slaXml = evalSla.evaluate(slaXml, String.class);
                // Validate against semantic SXD
                XmlUtils.validateData(slaXml, SchemaName.SLA_ORIGINAL);
            }
            catch (Exception e) {
                throw new CommandException(ErrorCode.E1004, "Validation ERROR :" + e.getMessage(), e);
            }
        }
    }

    /**
     * Resolve input-events/data-in and output-events/data-out tags.
     *
     * @param eJob : Job element
     * @throws CoordinatorJobException thrown if failed to resolve input and output events
     */
    @SuppressWarnings("unchecked")
    private void resolveIOEvents(Element eJobOrg, HashMap<String, String> dataNameList) throws CoordinatorJobException {
        // Resolving input-events/data-in
        // Clone the job and don't update anything in the original
        Element eJob = (Element) eJobOrg.clone();
        Element inputList = eJob.getChild("input-events", eJob.getNamespace());
        if (inputList != null) {
            TreeSet<String> eventNameSet = new TreeSet<String>();
            for (Element dataIn : (List<Element>) inputList.getChildren("data-in", eJob.getNamespace())) {
                String dataInName = dataIn.getAttributeValue("name");
                dataNameList.put(dataInName, "data-in");
                // check whether there is any duplicate data-in name
                if (eventNameSet.contains(dataInName)) {
                    throw new RuntimeException("Duplicate dataIn name " + dataInName);
                }
                else {
                    eventNameSet.add(dataInName);
                }
                resolveTagContents("instance", dataIn, evalInst);
                resolveTagContents("start-instance", dataIn, evalInst);
                resolveTagContents("end-instance", dataIn, evalInst);
            }
        }
        // Resolving output-events/data-out
        Element outputList = eJob.getChild("output-events", eJob.getNamespace());
        if (outputList != null) {
            TreeSet<String> eventNameSet = new TreeSet<String>();
            for (Element dataOut : (List<Element>) outputList.getChildren("data-out", eJob.getNamespace())) {
                String dataOutName = dataOut.getAttributeValue("name");
                dataNameList.put(dataOutName, "data-out");
                // check whether there is any duplicate data-out name
                if (eventNameSet.contains(dataOutName)) {
                    throw new RuntimeException("Duplicate dataIn name " + dataOutName);
                }
                else {
                    eventNameSet.add(dataOutName);
                }
                resolveTagContents("instance", dataOut, evalInst);
            }
        }

    }

    /**
     * Add an attribute into XML element.
     *
     * @param attrName :attribute name
     * @param elem : Element to add attribute
     * @param value :Value of attribute
     */
    private void addAnAttribute(String attrName, Element elem, String value) {
        elem.setAttribute(attrName, value);
    }

    /**
     * Resolve datasets using job configuration.
     *
     * @param eAppXml : Job Element XML
     * @throws Exception thrown if failed to resolve datasets
     */
    @SuppressWarnings("unchecked")
    private void resolveDataSets(Element eAppXml) throws Exception {
        Element datasetList = eAppXml.getChild("datasets", eAppXml.getNamespace());
        if (datasetList != null) {

            List<Element> dsElems = datasetList.getChildren("dataset", eAppXml.getNamespace());
            resolveDataSets(dsElems);
            resolveTagContents("app-path", eAppXml.getChild("action", eAppXml.getNamespace()).getChild("workflow",
                    eAppXml.getNamespace()), evalNofuncs);
        }
    }

    /**
     * Resolve datasets using job configuration.
     *
     * @param dsElems : Data set XML element.
     * @throws CoordinatorJobException thrown if failed to resolve datasets
     */
    private void resolveDataSets(List<Element> dsElems) throws CoordinatorJobException {
        for (Element dsElem : dsElems) {
            // Setting up default TimeUnit and EndOFDuraion
            evalFreq.setVariable("timeunit", TimeUnit.MINUTE);
            evalFreq.setVariable("endOfDuration", TimeUnit.NONE);

            String val = resolveAttribute("frequency", dsElem, evalFreq);
            int ival = ParamChecker.checkInteger(val, "frequency");
            ParamChecker.checkGTZero(ival, "frequency");
            addAnAttribute("freq_timeunit", dsElem, evalFreq.getVariable("timeunit") == null ? TimeUnit.MINUTE
                    .toString() : ((TimeUnit) evalFreq.getVariable("timeunit")).toString());
            addAnAttribute("end_of_duration", dsElem, evalFreq.getVariable("endOfDuration") == null ? TimeUnit.NONE
                    .toString() : ((TimeUnit) evalFreq.getVariable("endOfDuration")).toString());
            val = resolveAttribute("initial-instance", dsElem, evalNofuncs);
            ParamChecker.checkUTC(val, "initial-instance");
            val = resolveAttribute("timezone", dsElem, evalNofuncs);
            ParamChecker.checkTimeZone(val, "timezone");
            resolveTagContents("uri-template", dsElem, evalNofuncs);
            resolveTagContents("done-flag", dsElem, evalNofuncs);
        }
    }

    /**
     * Resolve the content of a tag.
     *
     * @param tagName : Tag name of job XML i.e. <timeout> 10 </timeout>
     * @param elem : Element where the tag exists.
     * @param eval : EL evealuator
     * @return Resolved tag content.
     * @throws CoordinatorJobException thrown if failed to resolve tag content
     */
    @SuppressWarnings("unchecked")
    private String resolveTagContents(String tagName, Element elem, ELEvaluator eval) throws CoordinatorJobException {
        String ret = "";
        if (elem != null) {
            for (Element tagElem : (List<Element>) elem.getChildren(tagName, elem.getNamespace())) {
                if (tagElem != null) {
                    String updated;
                    try {
                        updated = CoordELFunctions.evalAndWrap(eval, tagElem.getText().trim());

                    }
                    catch (Exception e) {
                        throw new CoordinatorJobException(ErrorCode.E1004, e.getMessage(), e);
                    }
                    tagElem.removeContent();
                    tagElem.addContent(updated);
                    ret += updated;
                }
            }
        }
        return ret;
    }

    /**
     * Resolve an attribute value.
     *
     * @param attrName : Attribute name.
     * @param elem : XML Element where attribute is defiend
     * @param eval : ELEvaluator used to resolve
     * @return Resolved attribute value
     * @throws CoordinatorJobException thrown if failed to resolve an attribute value
     */
    private String resolveAttribute(String attrName, Element elem, ELEvaluator eval) throws CoordinatorJobException {
        Attribute attr = elem.getAttribute(attrName);
        String val = null;
        if (attr != null) {
            try {
                val = CoordELFunctions.evalAndWrap(eval, attr.getValue().trim());

            }
            catch (Exception e) {
                throw new CoordinatorJobException(ErrorCode.E1004, e.getMessage(), e);
            }
            attr.setValue(val);
        }
        return val;
    }

    /**
     * Include referred datasets into XML.
     *
     * @param resolvedXml : Job XML element.
     * @param conf : Job configuration
     * @throws CoordinatorJobException thrown if failed to include referred datasets into XML
     */
    @SuppressWarnings("unchecked")
    protected void includeDataSets(Element resolvedXml, Configuration conf) throws CoordinatorJobException {
        Element datasets = resolvedXml.getChild("datasets", resolvedXml.getNamespace());
        Element allDataSets = new Element("all_datasets", resolvedXml.getNamespace());
        List<String> dsList = new ArrayList<String>();
        if (datasets != null) {
            for (Element includeElem : (List<Element>) datasets.getChildren("include", datasets.getNamespace())) {
                String incDSFile = includeElem.getTextTrim();
                includeOneDSFile(incDSFile, dsList, allDataSets, datasets.getNamespace());
            }
            for (Element e : (List<Element>) datasets.getChildren("dataset", datasets.getNamespace())) {
                String dsName = e.getAttributeValue("name");
                if (dsList.contains(dsName)) {// Override with this DS
                    // Remove old DS
                    removeDataSet(allDataSets, dsName);
                }
                else {
                    dsList.add(dsName);
                }
                allDataSets.addContent((Element) e.clone());
            }
        }
        insertDataSet(resolvedXml, allDataSets);
        resolvedXml.removeChild("datasets", resolvedXml.getNamespace());
    }

    /**
     * Include one dataset file.
     *
     * @param incDSFile : Include data set filename.
     * @param dsList :List of dataset names to verify the duplicate.
     * @param allDataSets : Element that includes all dataset definitions.
     * @param dsNameSpace : Data set name space
     * @throws CoordinatorJobException thrown if failed to include one dataset file
     */
    @SuppressWarnings("unchecked")
    private void includeOneDSFile(String incDSFile, List<String> dsList, Element allDataSets, Namespace dsNameSpace)
            throws CoordinatorJobException {
        Element tmpDataSets = null;
        try {
            String dsXml = readDefinition(incDSFile);
            log.debug("DSFILE :" + incDSFile + "\n" + dsXml);
            tmpDataSets = XmlUtils.parseXml(dsXml);
        }
        catch (JDOMException e) {
            log.warn("Error parsing included dataset [{0}].  Message [{1}]", incDSFile, e.getMessage());
            throw new CoordinatorJobException(ErrorCode.E0700, e.getMessage());
        }
        resolveDataSets(tmpDataSets.getChildren("dataset"));
        for (Element e : (List<Element>) tmpDataSets.getChildren("dataset")) {
            String dsName = e.getAttributeValue("name");
            if (dsList.contains(dsName)) {
                throw new RuntimeException("Duplicate Dataset " + dsName);
            }
            dsList.add(dsName);
            Element tmp = (Element) e.clone();
            // TODO: Don't like to over-write the external/include DS's namespace
            tmp.setNamespace(dsNameSpace);
            tmp.getChild("uri-template").setNamespace(dsNameSpace);
            if (e.getChild("done-flag") != null) {
                tmp.getChild("done-flag").setNamespace(dsNameSpace);
            }
            allDataSets.addContent(tmp);
        }
        // nested include
        for (Element includeElem : (List<Element>) tmpDataSets.getChildren("include", tmpDataSets.getNamespace())) {
            String incFile = includeElem.getTextTrim();
            includeOneDSFile(incFile, dsList, allDataSets, dsNameSpace);
        }
    }

    /**
     * Remove a dataset from a list of dataset.
     *
     * @param eDatasets : List of dataset
     * @param name : Dataset name to be removed.
     */
    @SuppressWarnings("unchecked")
    private static void removeDataSet(Element eDatasets, String name) {
        for (Element eDataset : (List<Element>) eDatasets.getChildren("dataset", eDatasets.getNamespace())) {
            if (eDataset.getAttributeValue("name").equals(name)) {
                eDataset.detach();
            }
        }
        throw new RuntimeException("undefined dataset: " + name);
    }

    /**
     * Read coordinator definition.
     *
     * @param appPath application path.
     * @return coordinator definition.
     * @throws CoordinatorJobException thrown if the definition could not be read.
     */
    protected String readDefinition(String appPath) throws CoordinatorJobException {
        String user = ParamChecker.notEmpty(conf.get(OozieClient.USER_NAME), OozieClient.USER_NAME);
        String group = ParamChecker.notEmpty(conf.get(OozieClient.GROUP_NAME), OozieClient.GROUP_NAME);
        // Configuration confHadoop = CoordUtils.getHadoopConf(conf);
        try {
            URI uri = new URI(appPath);
            log.debug("user =" + user + " group =" + group);
            FileSystem fs = Services.get().get(HadoopAccessorService.class).createFileSystem(user, group, uri,
                    new Configuration());
            Path p = new Path(uri.getPath());

            Reader reader = new InputStreamReader(fs.open(p));
            StringWriter writer = new StringWriter();
            IOUtils.copyCharStream(reader, writer);
            return writer.toString();
        }
        catch (IOException ex) {
            log.warn("IOException :" + XmlUtils.prettyPrint(conf), ex);
            throw new CoordinatorJobException(ErrorCode.E1001, ex.getMessage(), ex);
        }
        catch (URISyntaxException ex) {
            log.warn("URISyException :" + ex.getMessage());
            throw new CoordinatorJobException(ErrorCode.E1002, appPath, ex.getMessage(), ex);
        }
        catch (HadoopAccessorException ex) {
            throw new CoordinatorJobException(ex);
        }
        catch (Exception ex) {
            log.warn("Exception :", ex);
            throw new CoordinatorJobException(ErrorCode.E1001, ex.getMessage(), ex);
        }
    }

    /**
     * Write a coordinator job into database
     *
     * @param eJob : XML element of job
     * @param coordJob : Coordinator job bean
     * @return Job id
     * @throws CommandException thrown if unable to save coordinator job to db
     */
    private String storeToDB(Element eJob, CoordinatorJobBean coordJob) throws CommandException {
        String jobId = Services.get().get(UUIDService.class).generateId(ApplicationType.COORDINATOR);
        coordJob.setId(jobId);
        coordJob.setAuthToken(this.authToken);
        if (this.bundleId != null) {
            // this coord job is created from bundle
            coordJob.setBundleId(this.bundleId);
        }
        if (this.coordName != null) {
            // this coord job is created from bundle
            coordJob.setAppName(this.coordName);
        }
        else {
            coordJob.setAppName(eJob.getAttributeValue("name"));
        }
        coordJob.setAppPath(conf.get(OozieClient.COORDINATOR_APP_PATH));
        coordJob.setStatus(CoordinatorJob.Status.PREP);
        coordJob.setCreatedTime(new Date());
        coordJob.setUser(conf.get(OozieClient.USER_NAME));
        coordJob.setGroup(conf.get(OozieClient.GROUP_NAME));
        coordJob.setConf(XmlUtils.prettyPrint(conf).toString());
        coordJob.setJobXml(XmlUtils.prettyPrint(eJob).toString());
        coordJob.setLastActionNumber(0);
        coordJob.setLastModifiedTime(new Date());

        if (!dryrun) {
            coordJob.setLastModifiedTime(new Date());
            try {
                jpaService.execute(new CoordJobInsertJPAExecutor(coordJob));
            }
            catch (JPAExecutorException je) {
                throw new CommandException(je);
            }
        }
        return jobId;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#getEntityKey()
     */
    @Override
    protected String getEntityKey() {
        return null;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#isLockRequired()
     */
    @Override
    protected boolean isLockRequired() {
        return false;
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#loadState()
     */
    @Override
    protected void loadState() throws CommandException {
        jpaService = Services.get().get(JPAService.class);
        if (jpaService == null) {
            throw new CommandException(ErrorCode.E0610);
        }
    }

    /* (non-Javadoc)
     * @see org.apache.oozie.command.XCommand#verifyPrecondition()
     */
    @Override
    protected void verifyPrecondition() throws CommandException {

    }
}
