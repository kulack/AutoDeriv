package net.nodj.autoderivplugin.handlers;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import net.nodj.autoderivplugin.Conf;
import net.nodj.autoderivplugin.Cst;
import net.nodj.autoderivplugin.Debug;
import net.nodj.autoderivplugin.Filter;
import net.nodj.autoderivplugin.Tools;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;


/**@brief This class is the main IResourceChangeListener of the plug-in and must
 * react, update properties, etc.
 * @author johan duparc (johan.duparc@gmail.com)
 * @todo fast exit if the project is not managed.
 * @todo active polling on master file ?
 **/
public class ChangeEventHandler implements IResourceChangeListener{

	/** used to check if master conf file is updated */
	private static long previousMasterConfLastModified = 0;

	Object mutex = new Object();

	/**This is the main listener for this plugin. Any change on the workspace
	 * will call this method. We have to be prompt in here, else the UI will
	 * became less responsive. */
	@Override
	public void resourceChanged(final IResourceChangeEvent event) {
		// when a project is delete, quick exit as we don't care
		if(event.getType()==IResourceChangeEvent.PRE_DELETE) return;
		IResourceDelta delta = event.getDelta();
		if(delta==null) return;

		Debug.dbg("=====  ChangeEventHandler.resourceChanged()  =====");

		// Just debug prints
		switch(event.getType()){
		case IResourceChangeEvent.POST_CHANGE: Debug.dbg("POST_CHANGE"); break;
		case IResourceChangeEvent.POST_BUILD: Debug.dbg("POST_BUILD"); break;
		case IResourceChangeEvent.PRE_BUILD: Debug.dbg("PRE_BUILD"); break;
		case IResourceChangeEvent.PRE_CLOSE: Debug.dbg("PRE_CLOSE"); break;
		case IResourceChangeEvent.PRE_DELETE:
			// Occurs when a project is delete
			Debug.dbg("PRE_DELETE");
			return;
		case IResourceChangeEvent.PRE_REFRESH: Debug.dbg("PRE_REFRESH"); break;
		default: Debug.dbg("default... What ?"); break;
		}

		final HashMap<IProject, VisitData> perProjectVisitData = new HashMap<IProject, VisitData>();

		// loop in order to work on a per-projects basis
		for (IResourceDelta ac : delta.getAffectedChildren()) {
			VisitData v = new VisitData();
			IProject proj = ac.getResource().getProject();
			try {
//				progress.subTask("scan for project "+proj.getName());
				event.getDelta().accept(new MyDeltaVisitor(v));
			} catch (CoreException e1) {
				e1.printStackTrace();
			}

			// add the task to the list
			if(v.somethingToDo())
				perProjectVisitData.put(proj , v);
		}

		// also, check for master file edition
		final VisitData masterVisitData = handleMasterFile();

		// check if we could avoid the workspace job
		boolean nothingToDo = masterVisitData.nothingToDo() && perProjectVisitData.isEmpty();
		if(nothingToDo){
			Debug.dbg("Nothing to do for this change.");
			return;
		}
		Debug.dbg("Launch a new Workspace job for this change");

		// create the asynchronous working task.
		WorkspaceJob wj = new WorkspaceJob(Cst.PLUGIN_NAME + " - Refreshing status of modified elements...") {

			@Override
			public IStatus runInWorkspace(IProgressMonitor progress) throws CoreException {
				/* Well, this is not nice...
				 * This avoids possible ConcurrentModificationException but
				 * probably leads to unacceptable lag situations... */
				Debug.dbg("Workspace job start");
				IStatus status;
				synchronized (mutex) {
					status = doRunInWorkspace(progress);
				}
				Debug.dbg("Workspace job stop");
				return status;
			}

			public IStatus doRunInWorkspace(IProgressMonitor progress) throws CoreException {
				progress.beginTask("Handle changes", 100);

				// The delta visitor has now done its job : listing work to do.
				// Now let apply the change in a compact way (only one WorkspaceJob if possible)

				// handle MCF deletion
				if(masterVisitData.confDeleted){
					// delete project not handled by AutoDeriv anymore
					for(IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()){
						Filter filter = FilterManager.getByProj(project);

						// assert that the project was correctly handled
						if(filter == null){
							Debug.warn("unexpected case. project "+project.getName()+" was not handled, but a master file was here...");
							continue;
						}

						// delete project without local conf
						if(!filter.hasLocalConf())
							FilterManager.deleteFilter(project);
					}

					// for all managed projects, clear master rules (parsing null cause a clear)
					Filter.setMasterConfFile(null);
					Filter.parseMasterRules(FilterManager.getFilters(), progress);
				}

				progress.worked(10);

				// handle project creation
				ArrayList<Filter> addedProjecsFilter = new ArrayList<Filter>();
				for (Entry<IProject, VisitData> a : perProjectVisitData.entrySet()) {
					VisitData v = a.getValue();
					IProject proj = a.getKey();
					progress.subTask("Handle Creation of project "+proj.getName());

					if(v.projAdded){
						// handle master file
						if(Filter.hasMasterConf())
							addedProjecsFilter.add(FilterManager.getOrCreateFilter(proj));

						// handle local file
						v.confAdded = Filter.hasLocalConf(proj);
					}
				}

				boolean masterUpdate = masterVisitData.confAdded || masterVisitData.confUpdated;
				if(masterUpdate){
					// update projects Filters
					FilterManager.filterForAll();
					progress.subTask("Parse master conf rules");
					Filter.parseMasterRules(FilterManager.getFilters(), progress);
				}else{
					// even if master file hasn't changed, update for new projects
					if(!addedProjecsFilter.isEmpty())
						Filter.parseMasterRules(addedProjecsFilter, progress);
				}

				progress.worked(10);
				// handle per project VisitData
				for (Entry<IProject, VisitData> a : perProjectVisitData.entrySet()) {
					VisitData v = a.getValue();
					IProject proj = a.getKey();
					progress.subTask("Apply updates for project "+proj.getName());
					Filter f = null;

					// handle local configuration update
					if(v.confAdded || v.confUpdated){
						// filter the whole project with the new conf
						f = FilterManager.getOrCreateFilter(proj);
						f.reparseLocalConf(progress);
						if(!masterUpdate){
							// no need to update if the master conf is updated.
							// These projects will be updated after
							f.filterProject(progress);
						}
					}

					else if(v.projDeleted){
						FilterManager.deleteFilter(proj);
					}

					else if(v.confDeleted){
						if(Filter.hasMasterConf()){
							FilterManager.deleteFilter(proj);
						}
					}

					else{
						// this may not be a managed project
						f = FilterManager.getByProj(proj);
						if(f==null) continue;
						f.filterResources(v.added, progress);
					}
				}
				progress.worked(10);

				if(masterUpdate){
					// apply updates
					progress.subTask("Apply master conf update");
					FilterManager.filterWorkspace(progress);
				}else{
					// even if master didn't changed, filter new projects
					for(Filter f : addedProjecsFilter){
						f.filterProject(progress);
					}
				}
				progress.worked(10);

				Decorator.updateUI();

				return new Status(Status.OK, "AutoDeriv", "IResourceChangeEvent managed");
			}
		};
		wj.schedule();
	}


	/**Handle the master conf file.
	 * This MCF is not visible from eclipse. It is not subject to ChangeEvents
	 * or other nice Eclipse things. We have to handle the state evolution all
	 * by ourselves. Not nice...
	 * @return the VisitData that contains the main Debug.information from this state
	 * evolution */
	private VisitData handleMasterFile() {
		VisitData v = new VisitData();

		// get master conf. Ouch.
		File masterConfFile = ResourcesPlugin.getWorkspace().getRoot().getLocation().append(Cst.CONF_FILE_NAME).toFile();

		//!\\ note the subtle difference...
		boolean hasMasterConfFile = (masterConfFile!=null && masterConfFile.exists());
		boolean hadMasterConfFile = Filter.hasMasterConf();

		// also check the .metadata folder
		if(!hasMasterConfFile){
			masterConfFile = ResourcesPlugin.getWorkspace().getRoot().getLocation().append(".metadata").append(Cst.CONF_FILE_NAME).toFile();
			hasMasterConfFile = (masterConfFile!=null && masterConfFile.exists());
		}

		if(hasMasterConfFile){
			long masterConfLastModified = masterConfFile.lastModified();
			Filter.setMasterConfFile(masterConfFile);
			if(hadMasterConfFile){
				if(masterConfLastModified > previousMasterConfLastModified){
					v.confUpdated = true;
					Debug.info("Master Conf File UPDATED !");
				}// else, no evolution.
			}else{
				v.confAdded = true;
				Debug.info("Master Conf File ADDED !");
			}
			previousMasterConfLastModified = masterConfLastModified;
		}

		else {
			if(hadMasterConfFile){
				v.confDeleted = true;
				Debug.warn("master conf file deleted !");
				previousMasterConfLastModified = 0;
			}
			//		Debug.info{ Debug.warn("no master conf file, but it's not a big news..."); }
		}
		return v;
	}



	/**@brief manage the initial state. But don't waste time here. */
	public void startup() {
		Debug.info("=====  ChangeEventHandler.startup()  =====");
		WorkspaceJob wj = new WorkspaceJob(Cst.PLUGIN_NAME + " - Refreshing workspace...") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor progress) throws CoreException {
				progress.beginTask("startup", 100);
				synchronized (mutex) {
					deferedStartup(progress);
				}
				return new Status(Status.OK, "AutoDeriv", "Startup managed");
			}
		};
		wj.schedule();
	}

	/**Called at startup. In fact, called within a "WorkspaceJob"
	 * @param progress given IProgressMonitor that we update for UX reasons */
	private void deferedStartup(IProgressMonitor progress) {
		Debug.info("ChangeEventHandler.deferedStartup()");

		// avoid multiple startup() call mess
		if(!FilterManager.isEmpty()) return;

		// chrono
		double startupStart = Tools.getmsd();

		// Check masterfile
		Debug.dbg("ChangeEventHandler.deferedStartup() Check masterfile");
		VisitData masterVisit = handleMasterFile();
		boolean masterUpdated = masterVisit.confAdded || masterVisit.confUpdated;

		/* For each project, check if it contains a conf file. Parse it, but
		 * don't update files as it is a pure waste of time.
		 */
		for(IProject proj : ResourcesPlugin.getWorkspace().getRoot().getProjects()){
			if(!proj.isOpen()) continue;
			Debug.dbg("ChangeEventHandler.deferedStartup() on project ["+proj.getName()+"]");
			if(Filter.hasLocalConf(proj)){
				Debug.info("ChangeEventHandler.deferedStartup() project configured with AutoDeriv");
				Filter f = FilterManager.getOrCreateFilter(proj); // expected: Create only
				f.reparseLocalConf(progress);
			}
		}

		if(masterUpdated){
			// assert that all projects have a Filter. Master will affect all projects.
			FilterManager.filterForAll();

			// update projects Filters
			Filter.parseMasterRules(FilterManager.getFilters(), progress);
		}


		if(Conf.STARTUP_CHECK)
			FilterManager.filterWorkspace(progress);

		double startupEnd = Tools.getmsd();
		Debug.info("ChangeEventHandler.deferedStartup() took (ms) " + (startupEnd - startupStart));
	}

}
