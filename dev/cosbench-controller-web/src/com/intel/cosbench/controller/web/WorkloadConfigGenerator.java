package com.intel.cosbench.controller.web;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletRequest;

import com.intel.cosbench.config.Auth;
import com.intel.cosbench.config.Operation;
import com.intel.cosbench.config.Stage;
import com.intel.cosbench.config.Storage;
import com.intel.cosbench.config.Work;
import com.intel.cosbench.config.Workflow;
import com.intel.cosbench.config.Workload;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.Logger;
import com.intel.cosbench.service.ControllerService;

public class WorkloadConfigGenerator {

	private String runtime;
	private String delay;
	private String rampup;
	private String num_of_drivers;
	private String auth_type;
	private String auth_config;
	private String storage_type;
	private String storage_config;
	private boolean generateWorkloadFiles;

	protected ControllerService controller;

	private static final Logger LOGGER = LogFactory.getSystemLogger();

	private static final File WORKLOAD_CONFIG_DIR = new File("workload-configs");

	public WorkloadConfigGenerator(ControllerService controller) {
		this.controller = controller;
	}

	static {
		if (!WORKLOAD_CONFIG_DIR.exists())
			WORKLOAD_CONFIG_DIR.mkdirs();
		String path = WORKLOAD_CONFIG_DIR.getAbsolutePath();
		LOGGER.info("using {} for storing generated workload configs", path);
	}

	public void createWorkloadFiles(HttpServletRequest req) {

		// Set common workload parameters
		setWorkloadParams(req);

		String objectSizeStrings[] = req.getParameterValues("object-sizes");
		

		if (objectSizeStrings == null)
			return;

		for (int i = 0; i < objectSizeStrings.length; i++) {
			String objectSizes[], unit;
			boolean isRange;

			String objectSizeString = objectSizeStrings[i];
			// if input is range of object sizes
			if (objectSizeString.contains("-")) {
				objectSizes = objectSizeString.split("-");
				unit = req.getParameterValues("object-size-unit")[i];
				isRange = true;
			}
			// if input is comma separated object size values
			else {
				objectSizes = objectSizeString.split(",");
				unit = req.getParameterValues("object-size-unit")[i];
				isRange = false;
			}

			// parsing number of objects
			String objects[] = req.getParameterValues("num-of-objects")[i]
					.split(",");
			// parsing number of containers
			String containers[] = req.getParameterValues("num-of-containers")[i]
					.split(",");
			
			//Get read, write and delete ratios for one workload in string array.
			String rWDRatios[] = getRWDRatios(req,i);

			// parsing comma separated worker values
			String workers[] = req.getParameterValues("workers")[i].split(",");

			Workload workload = constructWorkload(req, objectSizes, containers,
					objects, workers, rWDRatios,
					isRange, unit);
			
			workload.validate();
				
			String workloadName = "objSizes-"+objectSizeString+unit;

			if (generateWorkloadFiles)
				printWorkloadConfigXML(workload, workloadName);
			else
				submitWorkload(workload);

		}
	}

	private String[] getRWDRatios(HttpServletRequest req, int workloadNumber) {
		String[] readRatios = req.getParameterValues("read-ratio"+workloadNumber);
		String[] writeRatios = req.getParameterValues("write-ratio"+workloadNumber);
		String[] deleteRatios = req.getParameterValues("delete-ratio"+workloadNumber);
		String[] rwdRatios = new String[readRatios.length];
		for(int i=0; i<readRatios.length; i++)
		{
			rwdRatios[i] = readRatios[i]+","+writeRatios[i]+","+deleteRatios[i];
		}
		return rwdRatios;
	}

	private void submitWorkload(Workload workload) {
		String workloadXml = CastorConfigTools.getWorkloadWriter()
				.toXmlString(workload);
		InputStream inputStream = new ByteArrayInputStream(workloadXml.getBytes());
		String id = controller.submit(new XmlConfig(inputStream));
		controller.fire(id);
	}

	private void printWorkloadConfigXML(Workload workload, String workloadName) {
		try {
			String workloadXml = CastorConfigTools.getWorkloadWriter()
					.toXmlString(workload);
			PrintWriter out = new PrintWriter(new File(WORKLOAD_CONFIG_DIR,workloadName+ ".xml"));
			out.print(workloadXml);
			out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

	}

	private void setWorkloadParams(HttpServletRequest req) {

		auth_type = req.getParameter("auth.type");
		auth_config = req.getParameter("auth.config");
		storage_type = req.getParameter("storage.type");
		storage_config = req.getParameter("storage.config");
		runtime = req.getParameter("runtime");
		delay = req.getParameter("delay");
		rampup = req.getParameter("rampup");
		num_of_drivers = req.getParameter("num_of_drivers");
		generateWorkloadFiles = req.getParameter("generate-workload") != null ? true
				: false;
	}

	public Workload constructWorkload(HttpServletRequest req,
			String[] objectSizes, String[] containers, String[] objects,
			String[] workers, String[] rwdRatios, boolean isRange, String unit) {
		int objectSizesLength;

		Workload workload = new Workload();

		String name = "workload"; // Need more work here
		String desc = "workload description"; // Need more work here

		workload.setName(name);
		workload.setDescription(desc);

		workload.setAuth(new Auth(auth_type, auth_config));
		workload.setStorage(new Storage(storage_type, storage_config));

		Workflow workflow = new Workflow();

		// If it is range of object sizes then set objectSizesLength to 1
		if (isRange)
			objectSizesLength = 1;
		else
			objectSizesLength = objectSizes.length;

		// Adding multiple init stages
		addInitStages(workflow, objectSizesLength, containers);

		// Adding multiple prepare stages
		addPrepareStages(workflow, objectSizesLength, objectSizes, objects,
				containers, isRange, unit);

		// Adding multiple normal stages
		addNormalStages(workflow, objectSizesLength, objectSizes, objects,
				containers, isRange, unit, workers, rwdRatios);

		// multiple cleanup stages
		addCleanupStages(workflow, objectSizesLength, objectSizes, objects,
				containers, isRange, unit);

		// multiple dispose stages
		addDisposeStages(workflow, objectSizesLength, containers);

		workload.setWorkflow(workflow);

		return workload;
	}

	private void addDisposeStages(Workflow workflow, int objectSizesLength,
			String[] containers) {

		int previousContainerValue = 0;
		for (int i = 0; i < objectSizesLength; i++) {
			for (int j = 0; j < containers.length; j++) {
				String containerString = containers[j];
				String from_container = previousContainerValue
						+ Integer.valueOf(containerString) + "";
				String to_container = Integer.valueOf(from_container)
						+ Integer.valueOf(containerString) - 1 + "";
				previousContainerValue = Integer.valueOf(to_container);
				Stage stage = createWorkstage(workflow, "dispose", "dispose", "1",
						"containers=r(" + from_container + "," + to_container
								+ ")");
				workflow.addStage(stage);
			}
		}
	}

	private void addCleanupStages(Workflow workflow, int objectSizesLength,
			String[] objectSizes, String[] objects, String[] containers,
			boolean isRange, String unit) {

		int previousContainerValue = 0;
		int previousObjectValue = 0;
		for (int i = 0; i < objectSizesLength; i++) {
			for (int j = 0; j < containers.length; j++) {
				for (int k = 0; k < objects.length; k++) {
					String containerString = containers[j];
					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);

					String from_object = previousObjectValue + 1 + "";
					String to_object = Integer.valueOf(from_object)
							+ Integer.valueOf(objects[k]) - 1 + "";
					previousObjectValue = Integer.valueOf(to_object);

					Stage stage = createWorkstage(workflow, "cleanup",
							"cleanup", "1", "containers=r(" + from_container
									+ "," + to_container + ");objects=r("
									+ from_object + "," + to_object + ")");
					workflow.addStage(stage);
				}
			}
		}

	}

	private void addNormalStages(Workflow workflow, int objectSizesLength,
			String[] objectSizes, String[] objects, String[] containers,
			boolean isRange, String unit, String[] workers,
			String[] rwdRatios) {
		int previousContainerValue = 0;
		int previousObjectValue = 0;
		for (int i = 0; i < objectSizesLength; i++) {
			for (int j = 0; j < containers.length; j++) {
				for (int k = 0; k < objects.length; k++) {
					String sizeString;
					if (isRange)
						sizeString = "(" + objectSizes[0] + ","
								+ objectSizes[1] + ")" + unit;
					else
						sizeString = "(" + objectSizes[i] + ")" + unit;

					String containerString = containers[j];
					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);

					String from_object = previousObjectValue + 1 + "";
					String to_object = Integer.valueOf(from_object)
							+ Integer.valueOf(objects[k]) - 1 + "";
					previousObjectValue = Integer.valueOf(to_object);

					for (int w = 0; w < workers.length; w++) {
						for (int r=0 ; r < rwdRatios.length; r++)
						{
							String[] readWriteDeleteRatios = rwdRatios[r].split(",");
							String readRatio = readWriteDeleteRatios[0];
							String writeRatio = readWriteDeleteRatios[1];
							String deleteRatio = readWriteDeleteRatios[2];
							
							Stage stage = createNormalWorkstage(workflow, workers[w],
									runtime, readRatio, writeRatio, deleteRatio,
									isRange, from_container, to_container,
									from_object, to_object, sizeString);
							workflow.addStage(stage);
						}
					}
				}
			}
		}

	}

	private void addPrepareStages(Workflow workflow, int objectSizesLength,
			String[] objectSizes, String[] objects, String[] containers,
			boolean isRange, String unit) {

		int previousContainerValue = 0;
		int previousObjectValue = 0;
		for (int i = 0; i < objectSizesLength; i++) {
			for (int j = 0; j < containers.length; j++) {
				for (int k = 0; k < objects.length; k++) {
					String sizeString;
					if (isRange)
						sizeString = "(" + objectSizes[0] + ","
								+ objectSizes[1] + ")" + unit;
					else
						sizeString = "(" + objectSizes[i] + ")" + unit;

					String containerString = containers[j];
					String from_container = previousContainerValue
							+ Integer.valueOf(containerString) + "";
					String to_container = Integer.valueOf(from_container)
							+ Integer.valueOf(containerString) - 1 + "";
					previousContainerValue = Integer.valueOf(to_container);

					String from_object = previousObjectValue + 1 + "";
					String to_object = Integer.valueOf(from_object)
							+ Integer.valueOf(objects[k]) - 1 + "";
					previousObjectValue = Integer.valueOf(to_object);

					if (isRange) {
						Stage stage = createWorkstage(workflow, "prepare",
								"prepare", Integer.valueOf(objects[k])
										/ Integer.valueOf(num_of_drivers) + "",
								"containers=r(" + from_container + ","
										+ to_container + ");objects=r("
										+ from_object + "," + to_object
										+ ");sizes=u" + sizeString);
						workflow.addStage(stage);
					} else {
						Stage stage = createWorkstage(workflow, "prepare",
								"prepare", Integer.valueOf(objects[k])
										/ Integer.valueOf(num_of_drivers) + "",
								"containers=r(" + from_container + ","
										+ to_container + ");objects=r("
										+ from_object + "," + to_object
										+ ");sizes=c" + sizeString);
						workflow.addStage(stage);
					}
				}
			}
		}
	}

	private void addInitStages(Workflow workflow, int objectSizesLength,
			String[] containers) {
		int previousContainerValue = 0;
		for (int i = 0; i < objectSizesLength; i++) {
			for (int j = 0; j < containers.length; j++) {
				String containerString = containers[j];
				String from_container = previousContainerValue
						+ Integer.valueOf(containerString) + "";
				String to_container = Integer.valueOf(from_container)
						+ Integer.valueOf(containerString) - 1 + "";
				previousContainerValue = Integer.valueOf(to_container);
				Stage stage = createWorkstage(workflow, "init", "init", "1",
						"containers=r(" + from_container + "," + to_container
								+ ")");
				workflow.addStage(stage);
			}
		}

	}

	private Stage createNormalWorkstage(Workflow workflow,
			String workers, String runtime, String readRatio,
			String writeRatio, String deleteRatio, boolean isRange,
			String from_container, String to_container, String from_object,
			String to_object, String sizeString) {

		String stageName = "w"
				+ sizeString
				+ "_c"
				+ (Integer.valueOf(to_container)
						- Integer.valueOf(from_container) + 1)
				+ "_o"
				+ (Integer.valueOf(to_object) - Integer.valueOf(from_object) + 1)
				+ "_r" + readRatio + "w" + writeRatio + "d" + deleteRatio + "_"
				+ workers;

		Stage stage = new Stage(stageName);

		stage.setClosuredelay(Integer.parseInt(delay));

		Work work = new Work("main", "normal");

		work.setWorkers(Integer.parseInt(workers));

		work.setRampup(Integer.parseInt(rampup));

		work.setRuntime(Integer.parseInt(runtime));

		stage.addWork(work);

		// Read operation
		Operation readOp = new Operation("read");

		readOp.setRatio(Integer.parseInt(readRatio));

		String readConfig = "containers=u(" + from_container + ","
				+ to_container + ");objects=u(" + from_object + "," + to_object
				+ ")";
		readOp.setConfig(readConfig);

		work.addOperation(readOp);

		// Write operation
		Operation writeOp = new Operation("write");

		writeOp.setRatio(Integer.parseInt(writeRatio));

		String config = "containers=u(" + from_container + "," + to_container
				+ ");objects=u(" + from_object + "," + to_object + ")";
		writeOp.setConfig(config);

		if (isRange) {
			String writeConfig = "containers=u(" + from_container + ","
					+ to_container + ");objects=u(" + from_object + ","
					+ to_object + ");sizes=u" + sizeString;
			writeOp.setConfig(writeConfig);

		} else {
			String writeConfig = "containers=u(" + from_container + ","
					+ to_container + ");objects=u(" + from_object + ","
					+ to_object + ");sizes=c" + sizeString;
			writeOp.setConfig(writeConfig);
		}

		work.addOperation(writeOp);

		// Delete operation

		Operation deleteOp = new Operation("delete");

		deleteOp.setRatio(Integer.parseInt(deleteRatio));

		String deleteConfig = "containers=u(" + from_container + ","
				+ to_container + ");objects=u(" + from_object + "," + to_object
				+ ")";

		deleteOp.setConfig(deleteConfig);

		work.addOperation(deleteOp);

		return stage;

	}

	private Stage createWorkstage(Workflow workflow, String stageName,
			String stageType, String workers, String config) {

		Stage stage = new Stage(stageName);

		Work work = new Work(stageName, stageType);

		work.setType(stageType);

		work.setWorkers(Integer.parseInt(workers));

		work.setConfig(config);

		stage.addWork(work);

		return stage;
	}
	
}