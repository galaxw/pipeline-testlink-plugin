package hudson.plugins.testlink.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.MalformedURLException;

import hudson.FilePath;
import hudson.Launcher;
import hudson.AbortException;
import hudson.Util;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.testlink.TestLinkBuilder;
import hudson.plugins.testlink.TestLinkInstallation;
import hudson.plugins.testlink.TestLinkBuilderDescriptor;
import hudson.plugins.testlink.TestLinkSite;
import hudson.plugins.testlink.Report;
import hudson.plugins.testlink.result.TestCaseWrapper;
import hudson.plugins.testlink.util.Messages;
import hudson.plugins.testlink.util.TestLinkHelper;
import hudson.plugins.testlink.util.ExecutionOrderComparator;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResultSummary;
import hudson.tasks.test.PipelineTestDetails;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResult;
import edu.umd.cs.findbugs.annotations.NonNull;

import io.jenkins.plugins.checks.steps.ChecksInfo;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import br.eti.kinoshita.testlinkjavaapi.model.TestPlan;
import br.eti.kinoshita.testlinkjavaapi.constants.ExecutionStatus;
import br.eti.kinoshita.testlinkjavaapi.model.CustomField;
import br.eti.kinoshita.testlinkjavaapi.model.TestCase;
import br.eti.kinoshita.testlinkjavaapi.util.TestLinkAPIException;

import static java.util.Objects.requireNonNull;

public class TestLinkStepExecution extends SynchronousNonBlockingStepExecution<Void> {

    private transient final TestLinkStep step;
    private static final Logger LOGGER = Logger.getLogger("hudson.plugins.testlink");

    /**
     * The Descriptor of this Builder. It contains the TestLink installation.
     */
    @Extension
    public static final TestLinkBuilderDescriptor DESCRIPTOR = new TestLinkBuilderDescriptor();

    public TestLinkStepExecution(@NonNull TestLinkStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    protected Void run() throws Exception {
        FilePath workspace = getContext().get(FilePath.class);
        workspace.mkdirs();
        Run<?, ?> run = requireNonNull(getContext().get(Run.class));
        TaskListener listener = getContext().get(TaskListener.class);
        Launcher launcher = getContext().get(Launcher.class);
        FlowNode node = getContext().get(FlowNode.class);

        String nodeId = node.getId();
        List<FlowNode> enclosingBlocks = getEnclosingStagesAndParallels(node);

        PipelineTestDetails pipelineTestDetails = new PipelineTestDetails();
        pipelineTestDetails.setNodeId(nodeId);
        pipelineTestDetails.setEnclosingBlocks(getEnclosingBlockIds(enclosingBlocks));
        pipelineTestDetails.setEnclosingBlockNames(getEnclosingBlockNames(enclosingBlocks));
        try {
            // If we are within a withChecks context, and have not provided a name override
            // in the step, apply the withChecks name
            final JUnitParser parser = new JUnitParser(false, false);
            final TestResult testResult = parser.parseResult(step.getIncludePattern(), run, pipelineTestDetails,
                    workspace, launcher, listener);
            LOGGER.log(Level.INFO, "Found test result: " + String.valueOf(testResult.getSuites().size()));

            // TestLink installation
            final TestLinkInstallation installation = DESCRIPTOR
                    .getInstallationByTestLinkName(step.getTestLinkName());
            if (installation == null) {
                throw new AbortException(Messages.TestLinkBuilder_InvalidTLAPI());
            }

            TestLinkHelper.setTestLinkJavaAPIProperties(installation.getTestLinkJavaAPIProperties(),
                    listener.getLogger());

            final TestLinkSite testLinkSite;
            final TestCaseWrapper[] automatedTestCases;
            final String testLinkUrl = installation.getUrl();
            final String testLinkDevKey = installation.getDevKey();
            LOGGER.log(Level.INFO, "testlink url: " + testLinkUrl + ", dev key: " + testLinkDevKey);
            TestPlan testPlan;
            try {
                final String testProjectName = step.getTestProjectName();
                final String testPlanName = step.getTestPlanName();
                final String platformName = step.getPlatformName();
                final String buildName = step.getBuildName();
                final String buildNotes = Messages.TestLinkBuilder_Build_Notes();
                listener.getLogger().println("TestLink project name: [" + testProjectName + "]");
                listener.getLogger().println("TestLink plan name: [" + testPlanName + "]");
                listener.getLogger().println("TestLink platform name: [" + platformName + "]");
                listener.getLogger().println("TestLink build name: [" + buildName + "]");
                listener.getLogger().println("TestLink build notes: [" + buildNotes + "]");
                // TestLink Site object
                testLinkSite = TestLinkHelper.getTestLinkSite(testLinkUrl, testLinkDevKey, testProjectName,
                        testPlanName,
                        platformName, buildName, buildNotes);

                if (StringUtils.isNotBlank(platformName) && testLinkSite.getPlatform() == null)
                    listener.getLogger().println(Messages.TestLinkBuilder_PlatformNotFound(platformName));

                final String[] testCaseCustomFieldsNames = step.getCustomFields().split(" ");
                // Array of automated test cases
                TestCase[] testCases = testLinkSite.getAutomatedTestCases(testCaseCustomFieldsNames);

                // Retrieve custom fields in test plan
                final String[] testPlanCustomFieldsNames = step.getTestPlanCustomFields().split(" ");
                testPlan = testLinkSite.getTestPlanWithCustomFields(testPlanCustomFieldsNames);

                // Transforms test cases into test case wrappers
                automatedTestCases = TestLinkHelper.transform(testCases);

                testCases = null;

                listener.getLogger()
                        .println(Messages.TestLinkBuilder_ShowFoundAutomatedTestCases(automatedTestCases.length));

                // Sorts test cases by each execution order (this info comes from
                // TestLink)
                listener.getLogger().println(Messages.TestLinkBuilder_SortingTestCases());
                Arrays.sort(automatedTestCases, new ExecutionOrderComparator());
            } catch (MalformedURLException mue) {
                mue.printStackTrace(listener.fatalError(mue.getMessage()));
                throw new AbortException(Messages.TestLinkBuilder_InvalidTLURL(testLinkUrl));
            } catch (TestLinkAPIException e) {
                e.printStackTrace(listener.fatalError(e.getMessage()));
                throw new AbortException(Messages.TestLinkBuilder_TestLinkCommunicationError());
            }

            for (TestCaseWrapper tcw : automatedTestCases) {
                testLinkSite.getReport().addTestCase(tcw);
                if (LOGGER.isLoggable(Level.INFO)) {
                    listener.getLogger().println(
                            "TestLink automated test case ID [" + tcw.getId() + "], name [" + tcw.getName() + "]");
                }
            }
            final Report report = testLinkSite.getReport();
            report.tally();

            listener.getLogger().println(Messages.TestLinkBuilder_ShowFoundTestResults(report.getTestsTotal()));

            LOGGER.log(Level.INFO, "TestLink builder finished");
            String keyCustomField = step.getKeyCustomField();

            for (SuiteResult suiteResult : testResult.getSuites()) {
                for (CaseResult caseResult : suiteResult.getCases()) {
                    if (LOGGER.isLoggable(Level.INFO)) {
                        listener.getLogger().println(caseResult.toPrettyString());
                    }
                    LOGGER.log(Level.INFO, caseResult.toPrettyString());
                    for (TestCaseWrapper automatedTestCase : automatedTestCases) {
                        final String[] commaSeparatedValues = automatedTestCase
                                .getKeyCustomFieldValues(keyCustomField);
                        if (LOGGER.isLoggable(Level.INFO)) {
                            listener.getLogger().println("this.keyCustomField: " + keyCustomField);
                        }
                        for (String value : commaSeparatedValues) {
                            if (LOGGER.isLoggable(Level.INFO)) {
                                listener.getLogger().println("caseResult.getName(): " + caseResult.getName());
                                listener.getLogger().println("value: " + value);
                            }
                            if (!caseResult.isSkipped() && caseResult.getName().equals(value)) {
                                ExecutionStatus status = TestLinkHelper.getExecutionStatus(caseResult);
                                automatedTestCase.addCustomFieldAndStatus(value, status);
                                automatedTestCase.setExecutionStatus(status);

                                final String notes = TestLinkHelper.getJUnitNotes(caseResult, run.getNumber());
                                automatedTestCase.appendNotes(notes);
                                int executionId = testLinkSite.updateTestCase(automatedTestCase);
                                listener.getLogger().println(
                                        "Update case " + caseResult.getName() + " by " + String.valueOf(executionId));
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            assert listener != null;
            listener.getLogger().println(e.getMessage());
            throw e;
        }
        return null;
    }

    /**
     * Get the stage and parallel branch start node IDs (not the body nodes) for
     * this node, innermost first.
     * 
     * @param node A flownode.
     * @return A nonnull, possibly empty list of stage/parallel branch start nodes,
     *         innermost first.
     */
    @NonNull
    public static List<FlowNode> getEnclosingStagesAndParallels(FlowNode node) {
        List<FlowNode> enclosingBlocks = new ArrayList<>();
        for (FlowNode enclosing : node.getEnclosingBlocks()) {
            if (enclosing != null && enclosing.getAction(LabelAction.class) != null) {
                if (isStageNode(enclosing) ||
                        (enclosing.getAction(ThreadNameAction.class) != null)) {
                    enclosingBlocks.add(enclosing);
                }
            }
        }

        return enclosingBlocks;
    }

    private static boolean isStageNode(@NonNull FlowNode node) {
        if (node instanceof StepNode) {
            StepDescriptor d = ((StepNode) node).getDescriptor();
            return d != null && d.getFunctionName().equals("stage");
        } else {
            return false;
        }
    }

    @NonNull
    public static List<String> getEnclosingBlockIds(@NonNull List<FlowNode> nodes) {
        List<String> ids = new ArrayList<>();
        for (FlowNode n : nodes) {
            ids.add(n.getId());
        }
        return ids;
    }

    @NonNull
    public static List<String> getEnclosingBlockNames(@NonNull List<FlowNode> nodes) {
        List<String> names = new ArrayList<>();
        for (FlowNode n : nodes) {
            ThreadNameAction threadNameAction = n.getPersistentAction(ThreadNameAction.class);
            LabelAction labelAction = n.getPersistentAction(LabelAction.class);
            if (threadNameAction != null) {
                // If we're on a parallel branch with the same name as the previous (inner)
                // node, that generally
                // means we're in a Declarative parallel stages situation, so don't add the
                // redundant branch name.
                if (names.isEmpty() || !threadNameAction.getThreadName().equals(names.get(names.size() - 1))) {
                    names.add(threadNameAction.getThreadName());
                }
            } else if (labelAction != null) {
                names.add(labelAction.getDisplayName());
            }
        }
        return names;
    }

    private static final long serialVersionUID = 1L;

    /**
     * @param cases
     * @return
     */
    private List<CaseResult> filter(List<CaseResult> cases) {
        final List<CaseResult> filtered = new LinkedList<CaseResult>();

        for (CaseResult caseResult : cases) {
            final CaseResult c = this.find(filtered, caseResult);
            if (c != null) {
                if (c.getFailCount() <= 0) { // didn't fail
                    this.remove(filtered, c);
                    filtered.add(caseResult);
                }
            } else {
                filtered.add(caseResult);
            }
        }

        return filtered;
    }

    /**
     * @param filtered
     * @param caseResult
     * @return
     */
    private CaseResult find(List<CaseResult> filtered, CaseResult caseResult) {
        for (CaseResult c : filtered) {
            if (c.getClassName().equals(caseResult.getClassName())) {
                return c;
            }
        }
        return null;
    }

    /**
     * @param filtered
     * @param caseResult
     * @return
     */
    private void remove(List<CaseResult> filtered, CaseResult caseResult) {
        final Iterator<CaseResult> iterator = filtered.iterator();
        while (iterator.hasNext()) {
            CaseResult c = iterator.next();
            if (c.getClassName().equals(caseResult.getClassName())) {
                iterator.remove();
            }
        }
    }
}
