package dk.statsbiblioteket.mediaplatform.ingest.mediafilesinitiator.mock;

import java.util.Date;

import dk.statsbiblioteket.mediaplatform.ingest.mediafilesinitiator.WorkFlowStateMonitorFacade;
import dk.statsbiblioteket.medieplatform.workflowstatemonitor.Entity;
import dk.statsbiblioteket.medieplatform.workflowstatemonitor.State;

public class WorkFlowStateMonitorFacadeStub implements
        WorkFlowStateMonitorFacade {

    private State state;
    
    public WorkFlowStateMonitorFacadeStub() {
        this.state = null;
    }

    public WorkFlowStateMonitorFacadeStub(String component, Date date, String sbFilenameId, String stateName) {
        this.state = generateState(component, date, sbFilenameId, stateName);
    }

    @Override
    public State getLastWorkFlowStateForEntity(String sbFileId) {
        return state;
    }

    @Override
    public void addState(String stateName, String message) {
        //Does nothing in stub.
    }

    protected State generateState(String component, Date date, String sbFilenameId, String stateName) {
        State state = new State();
        state.setComponent(component);
        state.setDate(date);
        Entity entity = new Entity();
        entity.setName(sbFilenameId);
        state.setEntity(entity);
        state.setMessage("Message");
        state.setStateName(stateName);
        return state;
    }

}
