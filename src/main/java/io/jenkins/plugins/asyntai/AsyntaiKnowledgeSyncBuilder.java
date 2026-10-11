package io.jenkins.plugins.asyntai;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Build step that sends documentation files from the workspace to the
 * Asyntai knowledge base. Works in freestyle jobs and in Pipeline as
 * {@code asyntaiKnowledgeSync}.
 */
public class AsyntaiKnowledgeSyncBuilder extends Builder implements SimpleBuildStep {

    static final String DEFAULT_FILES = "docs/**/*.md";

    private final String credentialsId;
    private String files = DEFAULT_FILES;
    private String excludes = "";
    private String rootDirectory = "";
    private String titlePrefix = "";
    private String websiteId = "";
    private boolean prune;
    private boolean dryRun;
    private String apiUrl = AsyntaiClient.DEFAULT_API_URL;

    @DataBoundConstructor
    public AsyntaiKnowledgeSyncBuilder(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getFiles() {
        return files;
    }

    @DataBoundSetter
    public void setFiles(String files) {
        this.files = Util.fixEmptyAndTrim(files) == null ? DEFAULT_FILES : files.trim();
    }

    public String getExcludes() {
        return excludes;
    }

    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = Util.fixNull(excludes).trim();
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    @DataBoundSetter
    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = Util.fixNull(rootDirectory).trim();
    }

    public String getTitlePrefix() {
        return titlePrefix;
    }

    @DataBoundSetter
    public void setTitlePrefix(String titlePrefix) {
        // Spaces are kept on purpose: the prefix is part of the entry title.
        this.titlePrefix = Util.fixNull(titlePrefix);
    }

    public String getWebsiteId() {
        return websiteId;
    }

    @DataBoundSetter
    public void setWebsiteId(String websiteId) {
        this.websiteId = Util.fixNull(websiteId).trim();
    }

    public boolean isPrune() {
        return prune;
    }

    @DataBoundSetter
    public void setPrune(boolean prune) {
        this.prune = prune;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    @DataBoundSetter
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = Util.fixEmptyAndTrim(apiUrl) == null ? AsyntaiClient.DEFAULT_API_URL : apiUrl.trim();
    }

    /** Splits a field that holds patterns one per line or separated by commas. */
    static List<String> splitPatterns(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split("[\\r\\n,]+"))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toList());
    }

    /** Path of {@code file} relative to {@code root}, always with forward slashes. */
    static String relativePath(FilePath root, FilePath file) {
        String base = root.getRemote().replace('\\', '/');
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String path = file.getRemote().replace('\\', '/');
        return path.startsWith(base) ? path.substring(base.length()) : file.getName();
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream log = listener.getLogger();
        log.println("[Asyntai] Knowledge Sync" + (dryRun ? " (dry run, nothing is sent)" : ""));

        if (credentialsId == null) {
            throw new AbortException("[Asyntai] Choose the credential that holds your Asyntai API key.");
        }
        StringCredentials credentials =
                CredentialsProvider.findCredentialById(credentialsId, StringCredentials.class, run);
        if (credentials == null) {
            throw new AbortException("[Asyntai] No Secret text credential with the ID '" + credentialsId
                    + "' was found. Add your Asyntai API key as a Secret text credential.");
        }
        String apiKey = credentials.getSecret().getPlainText().trim();
        if (apiKey.isEmpty()) {
            throw new AbortException("[Asyntai] The credential '" + credentialsId + "' is empty.");
        }

        FilePath root = rootDirectory.isEmpty() ? workspace : workspace.child(env.expand(rootDirectory));
        if (!root.isDirectory()) {
            throw new AbortException("[Asyntai] The folder " + root.getRemote() + " does not exist.");
        }

        List<String> includePatterns = splitPatterns(env.expand(files));
        List<String> excludePatterns = splitPatterns(env.expand(excludes));
        if (includePatterns.isEmpty()) {
            includePatterns = List.of(DEFAULT_FILES);
        }
        String includes = String.join(",", includePatterns);

        FilePath[] matched = root.list(includes, excludePatterns.isEmpty() ? null : String.join(",", excludePatterns));
        List<KnowledgeSync.SourceFile> sources = new ArrayList<>();
        Arrays.sort(matched, Comparator.comparing(f -> relativePath(root, f)));
        for (FilePath file : matched) {
            String path = relativePath(root, file);
            try (InputStream in = file.read()) {
                sources.add(new KnowledgeSync.SourceFile(path, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }

        if (sources.isEmpty()) {
            log.println("[Asyntai] WARNING: no file matched " + includes + ". Nothing was sent.");
            run.addAction(new KnowledgeSyncAction(new KnowledgeSync.Result(), dryRun));
            return;
        }
        log.println("[Asyntai] " + sources.size() + " file(s) matched " + includes + ".");

        KnowledgeSync.Options options = new KnowledgeSync.Options()
                .titlePrefix(env.expand(titlePrefix))
                .websiteId(env.expand(websiteId))
                .prune(prune)
                .dryRun(dryRun);

        KnowledgeSync.Result result;
        try {
            result = KnowledgeSync.run(sources, new AsyntaiClient(env.expand(apiUrl), apiKey), options, log);
        } catch (ApiException e) {
            throw new AbortException("[Asyntai] Could not read the knowledge base: " + e.getMessage());
        }

        run.addAction(new KnowledgeSyncAction(result, dryRun));
        log.println("[Asyntai] Added " + result.getAdded() + ", updated " + result.getUpdated() + ", unchanged "
                + result.getUnchanged() + ", deleted " + result.getDeleted() + ", skipped " + result.getSkipped()
                + ".");

        if (result.getFailed() > 0) {
            throw new AbortException("[Asyntai] " + result.getFailed() + " file(s) failed.");
        }
    }

    @Symbol("asyntaiKnowledgeSync")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Asyntai Knowledge Sync";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDefaultFiles() {
            return DEFAULT_FILES;
        }

        public String getDefaultApiUrl() {
            return AsyntaiClient.DEFAULT_API_URL;
        }

        private static boolean cannotConfigure(Item item) {
            if (item == null) {
                return !Jenkins.get().hasPermission(Jenkins.ADMINISTER);
            }
            return !item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(CredentialsProvider.USE_ITEM);
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (cannotConfigure(item)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeAs(ACL.SYSTEM2, item, StringCredentials.class)
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doCheckCredentialsId(@AncestorInPath Item item, @QueryParameter String value) {
            if (cannotConfigure(item)) {
                return FormValidation.ok();
            }
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Choose the Secret text credential that holds your Asyntai API key.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckApiUrl(@AncestorInPath Item item, @QueryParameter String value) {
            if (cannotConfigure(item)) {
                return FormValidation.ok();
            }
            String url = Util.fixEmptyAndTrim(value);
            if (url != null && !url.startsWith("https://") && !url.startsWith("http://")) {
                return FormValidation.error("The address must start with https://");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckPrune(
                @AncestorInPath Item item, @QueryParameter boolean value, @QueryParameter String titlePrefix) {
            if (cannotConfigure(item)) {
                return FormValidation.ok();
            }
            if (value && Util.fixEmpty(titlePrefix) == null) {
                return FormValidation.warning("Set a title prefix as well. Without it, prune is ignored.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doTestConnection(
                @AncestorInPath Item item,
                @QueryParameter String credentialsId,
                @QueryParameter String apiUrl,
                @QueryParameter String websiteId) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
            if (Util.fixEmptyAndTrim(credentialsId) == null) {
                return FormValidation.error("Choose a credential first.");
            }
            StringCredentials credentials = CredentialsMatchers.firstOrNull(
                    item == null
                            ? CredentialsProvider.lookupCredentialsInItemGroup(
                                    StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of())
                            : CredentialsProvider.lookupCredentialsInItem(
                                    StringCredentials.class, item, ACL.SYSTEM2, List.of()),
                    CredentialsMatchers.withId(credentialsId));
            if (credentials == null) {
                return FormValidation.error("The credential '" + credentialsId + "' was not found.");
            }
            try {
                List<KnowledgeApi.Entry> entries = new AsyntaiClient(
                                apiUrl, credentials.getSecret().getPlainText().trim())
                        .list(websiteId);
                String count = entries.size() >= 100 ? "100 or more" : String.valueOf(entries.size());
                return FormValidation.ok("Connected. The knowledge base holds " + count + " entries.");
            } catch (ApiException e) {
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
    }
}
