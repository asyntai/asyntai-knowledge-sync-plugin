package io.jenkins.plugins.asyntai;

import hudson.model.InvisibleAction;
import java.util.List;

/** Keeps the result of one sync with the build and shows it on the build page. */
public class KnowledgeSyncAction extends InvisibleAction {

    private final KnowledgeSync.Result result;
    private final boolean dryRun;

    public KnowledgeSyncAction(KnowledgeSync.Result result, boolean dryRun) {
        this.result = result;
        this.dryRun = dryRun;
    }

    public KnowledgeSync.Result getResult() {
        return result;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public List<KnowledgeSync.Row> getRows() {
        return result.getRows();
    }
}
