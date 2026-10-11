package io.jenkins.plugins.asyntai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.util.Secret;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Runs the step inside a real Jenkins, in freestyle jobs and in Pipeline, against the stub API. */
@WithJenkins
class AsyntaiKnowledgeSyncBuilderTest {

    private static final String CREDENTIALS_ID = "asyntai-api-key";

    private JenkinsRule j;
    private StubApi stub;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        stub = new StubApi();
        addKey(CREDENTIALS_ID, StubApi.KEY);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private static void addKey(String id, String secret) throws IOException {
        CredentialsProvider.lookupStores(jenkins.model.Jenkins.get())
                .iterator()
                .next()
                .addCredentials(
                        Domain.global(),
                        new StringCredentialsImpl(
                                CredentialsScope.GLOBAL, id, "Asyntai API key", Secret.fromString(secret)));
    }

    /** Writes files into the workspace, the way a checkout would. */
    private static final class WriteFiles extends TestBuilder {
        private final Map<String, String> files;

        WriteFiles(Map<String, String> files) {
            this.files = files;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            FilePath ws = build.getWorkspace();
            for (Map.Entry<String, String> file : files.entrySet()) {
                ws.child(file.getKey()).write(file.getValue(), "UTF-8");
            }
            return true;
        }
    }

    private AsyntaiKnowledgeSyncBuilder step() {
        AsyntaiKnowledgeSyncBuilder step = new AsyntaiKnowledgeSyncBuilder(CREDENTIALS_ID);
        step.setApiUrl(stub.url());
        return step;
    }

    @Test
    void freestyleFirstRunThenUnchanged() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList()
                .add(new WriteFiles(Map.of(
                        "docs/intro.md", "---\ntitle: Intro\n---\nWelcome to the product handbook.",
                        "docs/guide/setup.md", "How to set up the product, step by step.",
                        "docs/notes.txt", "Not a markdown file, so not sent.",
                        "README.md", "Root readme is outside docs.")));
        project.getBuildersList().add(step());

        FreeStyleBuild first = j.buildAndAssertSuccess(project);
        assertEquals(List.of("docs/guide/setup.md", "docs/intro.md"), stub.titles());
        assertEquals("Welcome to the product handbook.", stub.contentOf("docs/intro.md"));
        KnowledgeSyncAction action = first.getAction(KnowledgeSyncAction.class);
        assertNotNull(action);
        assertEquals(2, action.getResult().getAdded());
        j.assertLogContains("2 file(s) matched docs/**/*.md.", first);
        j.assertLogNotContains(StubApi.KEY, first);

        FreeStyleBuild second = j.buildAndAssertSuccess(project);
        assertEquals(2, second.getAction(KnowledgeSyncAction.class).getResult().getUnchanged());
        assertEquals(2, stub.posts.get());

        // The summary is shown on the build page.
        HtmlPage page = j.createWebClient().getPage(second);
        String text = page.asNormalizedText();
        assertTrue(text.contains("Asyntai Knowledge Sync"), text);
        assertTrue(text.contains("unchanged 2"), text);
        assertTrue(text.contains("docs/intro.md"), text);
    }

    @Test
    void pipelineSymbolPrefixPruneAndRootFolder() throws Exception {
        stub.put("site/old.md", "This page was removed from the repository.");
        stub.put("other.md", "An entry the job does not own at all.");
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'website/content/a.md', text: 'Alpha page of the website content.'\n"
                        + "  writeFile file: 'website/content/drafts/b.md', text: 'Draft page that must stay out.'\n"
                        + "  writeFile file: 'website/content/c.markdown', text: 'Gamma page in another extension.'\n"
                        + "  asyntaiKnowledgeSync credentialsId: '" + CREDENTIALS_ID + "',\n"
                        + "    rootDirectory: 'website',\n"
                        + "    files: '''content/**/*.md\n"
                        + "content/**/*.markdown''',\n"
                        + "    excludes: 'content/drafts/**',\n"
                        + "    titlePrefix: 'site/',\n"
                        + "    prune: true,\n"
                        + "    apiUrl: '" + stub.url() + "'\n"
                        + "}\n",
                true));

        WorkflowRun run = j.buildAndAssertSuccess(job);
        assertEquals(List.of("other.md", "site/content/a.md", "site/content/c.markdown"), stub.titles());
        j.assertLogContains("deleted: site/old.md", run);
        j.assertLogNotContains(StubApi.KEY, run);
    }

    @Test
    void declarativePipeline() throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "declarative");
        job.setDefinition(new CpsFlowDefinition(
                "pipeline {\n"
                        + "  agent any\n"
                        + "  stages {\n"
                        + "    stage('Docs') {\n"
                        + "      steps {\n"
                        + "        writeFile file: 'docs/a.md', text: 'Alpha page with enough text.'\n"
                        + "        asyntaiKnowledgeSync credentialsId: '" + CREDENTIALS_ID + "', apiUrl: '"
                        + stub.url() + "'\n"
                        + "      }\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n",
                true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("added: docs/a.md", run);
        assertEquals(List.of("docs/a.md"), stub.titles());
    }

    @Test
    void pipelineDryRunSendsNothing() throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "dry");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'docs/a.md', text: 'Alpha page with enough text.'\n"
                        + "  asyntaiKnowledgeSync credentialsId: '" + CREDENTIALS_ID + "', dryRun: true, apiUrl: '"
                        + stub.url() + "'\n"
                        + "}\n",
                true));
        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("would add: docs/a.md", run);
        assertEquals(0, stub.posts.get());
        assertTrue(run.getAction(KnowledgeSyncAction.class).isDryRun());
    }

    @Test
    void noMatchingFileIsAWarningNotAFailure() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(step());
        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("no file matched docs/**/*.md. Nothing was sent.", build);
        assertEquals(0, stub.lists.get());
    }

    @Test
    void missingCredentialFailsTheBuild() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        AsyntaiKnowledgeSyncBuilder missing = new AsyntaiKnowledgeSyncBuilder("does-not-exist");
        missing.setApiUrl(stub.url());
        project.getBuildersList().add(missing);
        FreeStyleBuild build = j.buildAndAssertStatus(Result.FAILURE, project);
        j.assertLogContains("No Secret text credential with the ID 'does-not-exist' was found.", build);
    }

    @Test
    void badKeyFailsTheBuild() throws Exception {
        addKey("bad-key", "not-the-right-key");
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(new WriteFiles(Map.of("docs/a.md", "Alpha page with enough text.")));
        AsyntaiKnowledgeSyncBuilder bad = new AsyntaiKnowledgeSyncBuilder("bad-key");
        bad.setApiUrl(stub.url());
        project.getBuildersList().add(bad);
        FreeStyleBuild build = j.buildAndAssertStatus(Result.FAILURE, project);
        j.assertLogContains("Could not read the knowledge base: Invalid API key", build);
        j.assertLogNotContains("not-the-right-key", build);
    }

    @Test
    void failedUploadFailsTheBuild() throws Exception {
        stub.rateLimitAfter = 0;
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(new WriteFiles(Map.of("docs/a.md", "Alpha page with enough text.")));
        project.getBuildersList().add(step());
        FreeStyleBuild build = j.buildAndAssertStatus(Result.FAILURE, project);
        j.assertLogContains("FAILED: docs/a.md: Daily upload limit reached", build);
        j.assertLogContains("1 file(s) failed.", build);
    }

    @Test
    void missingRootFolderFailsTheBuild() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        AsyntaiKnowledgeSyncBuilder step = step();
        step.setRootDirectory("no-such-folder");
        project.getBuildersList().add(step);
        FreeStyleBuild build = j.buildAndAssertStatus(Result.FAILURE, project);
        j.assertLogContains("does not exist.", build);
    }

    @Test
    void configRoundTripKeepsEveryField() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        AsyntaiKnowledgeSyncBuilder before = new AsyntaiKnowledgeSyncBuilder(CREDENTIALS_ID);
        before.setFiles("docs/**/*.md\nguides/*.md");
        before.setExcludes("docs/drafts/**");
        before.setRootDirectory("site");
        before.setTitlePrefix("handbook/");
        before.setWebsiteId("42");
        before.setPrune(true);
        before.setDryRun(true);
        before.setApiUrl("https://example.com");
        project.getBuildersList().add(before);

        project = j.configRoundtrip(project);
        AsyntaiKnowledgeSyncBuilder after = project.getBuildersList().get(AsyntaiKnowledgeSyncBuilder.class);
        j.assertEqualDataBoundBeans(before, after);
    }

    @Test
    void defaultsAndPatternSplitting() {
        AsyntaiKnowledgeSyncBuilder step = new AsyntaiKnowledgeSyncBuilder(" key ");
        assertEquals("key", step.getCredentialsId());
        assertEquals("docs/**/*.md", step.getFiles());
        assertEquals("https://asyntai.com", step.getApiUrl());
        step.setFiles("  ");
        assertEquals("docs/**/*.md", step.getFiles());
        assertFalse(step.isPrune());
        assertEquals(
                List.of("a/*.md", "b/**", "c.md"),
                AsyntaiKnowledgeSyncBuilder.splitPatterns(" a/*.md ,\r\nb/** \n\n c.md "));
    }
}
