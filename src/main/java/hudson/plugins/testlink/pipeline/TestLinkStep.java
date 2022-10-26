package hudson.plugins.testlink.pipeline;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import hudson.tasks.test.PipelineTestDetails;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;

public class TestLinkStep extends Step {
    private static final Logger LOGGER = Logger.getLogger("hudson.plugins.testlink");
    /* --- Job properties --- */
    /**
     * The name of the TestLink installation.
     */
    protected final String testLinkName;
    /**
     * The name of the Test Project.
     */
    protected final String testProjectName;
    /**
     * The name of the Test Plan.
     */
    protected final String testPlanName;
    /**
     * The name of the Build.
     */
    protected String buildName;
    /**
     * The platform name.
     */
    protected final String platformName;
    /**
     * Comma separated list of custom fields to download from TestLink.
     */
    protected final String customFields;

    /**
     * Comma separated list of test plan custom fields to download from TestLink.
     */
    protected final String testPlanCustomFields;
    /**
     * Include pattern used when looking for results.
     */
    protected final String includePattern;

    /**
     * Key custom field.
     */
    protected final String keyCustomField;

    /**
     * Whether the plug-in must include notes when updating test cases.
     */
    protected final boolean includeNotes;

    @DataBoundConstructor
    public TestLinkStep(String testLinkName, String testProjectName,
            String testPlanName, String platformName, String buildName, String customFields,
            String testPlanCustomFields, String includePattern, String keyCustomField) {
        super();
        this.testLinkName = testLinkName;
        this.testProjectName = testProjectName;
        this.testPlanName = testPlanName;
        this.platformName = platformName;
        this.buildName = buildName;
        this.customFields = customFields;
        this.testPlanCustomFields = testPlanCustomFields;
        this.includeNotes = false;
        this.includePattern = includePattern;
        this.keyCustomField = keyCustomField;
        System.out.println("TestLinkStep is inited.");
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new TestLinkStepExecution(this, context);
    }

    public String getTestLinkName() {
        return this.testLinkName;
    }

    public String getTestProjectName() {
        return this.testProjectName;
    }

    public String getTestPlanName() {
        return this.testPlanName;
    }

    public String getPlatformName() {
        return this.platformName;
    }

    public String getBuildName() {
        return this.buildName;
    }

    public String getCustomFields() {
        return this.customFields;
    }

    public String getTestPlanCustomFields() {
        return this.testPlanCustomFields;
    }

    /**
     * @return the includePattern
     */
    public String getIncludePattern() {
        return includePattern;
    }

    /**
     * @return the keyCustomField
     */
    public String getKeyCustomField() {
        return keyCustomField;
    }

    /**
     * @return the enableNotes
     */
    public boolean isIncludeNotes() {
        return includeNotes;
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "testlink";
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "Upload testlink results";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            Set<Class<?>> context = new HashSet<>();
            Collections.addAll(context, FilePath.class, FlowNode.class, TaskListener.class,
                    Launcher.class);
            return Collections.unmodifiableSet(context);
        }
    }

}
