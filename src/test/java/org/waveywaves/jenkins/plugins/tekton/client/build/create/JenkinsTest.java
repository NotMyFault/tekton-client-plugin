package org.waveywaves.jenkins.plugins.tekton.client.build.create;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRunBuilder;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRunSpec;
import io.fabric8.tekton.pipeline.v1beta1.PipelineTask;
import io.fabric8.tekton.pipeline.v1beta1.Task;
import io.fabric8.tekton.pipeline.v1beta1.TaskBuilder;
import io.fabric8.tekton.pipeline.v1beta1.TaskList;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunBuilder;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunList;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunListBuilder;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.commons.io.IOUtils;
import org.assertj.core.internal.InputStreams;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.waveywaves.jenkins.plugins.tekton.client.TektonUtils;
import org.waveywaves.jenkins.plugins.tekton.client.ToolUtils;
import org.waveywaves.jenkins.plugins.tekton.client.global.ClusterConfig;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class JenkinsTest {

    public JenkinsRule jenkinsRule = new JenkinsRule();
    public KubernetesServer kubernetesRule = new KubernetesServer();

    @Rule
    public TestRule chain =
            RuleChain.outerRule(kubernetesRule)
                    .around(jenkinsRule);

    @Before
    public void before() {
        KubernetesClient client = kubernetesRule.getClient();
        Config config = client.getConfiguration();
        TektonUtils.initializeKubeClients(config);
    }

    @Test
    public void testScriptedPipeline() throws Exception {
        TaskBuilder taskBuilder = new TaskBuilder()
                .withNewMetadata().withName("testTask").endMetadata();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/tasks")
                .andReturn(HttpURLConnection.HTTP_OK, taskBuilder.build()).once();

        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setDefinition(new CpsFlowDefinition("node {\n"
                                              + "  unzip '" + zipFile.getPath() + "'\n"
                                              + "  tektonCreateRaw(inputType: 'FILE', input: '.tekton/task.yaml')\n"
                                              + "}\n", true));

        WorkflowRun b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(1));

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Extracting: .tekton/task.yaml"));
        assertThat(log, containsString("[Pipeline] tektonCreateRaw"));
        assertThat(log, not(containsString(".tekton/task.yaml (No such file or directory)")));
    }

    @Test
    public void testDeclarativePipelineWithFileInput() throws Exception {
        TaskBuilder taskBuilder = new TaskBuilder()
                .withNewMetadata().withName("testTask").endMetadata();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/tasks")
                .andReturn(HttpURLConnection.HTTP_OK, taskBuilder.build()).once();

        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setDefinition(new CpsFlowDefinition("pipeline { \n"
                                              + "  agent any\n"
                                              + "  stages {\n"
                                              + "    stage('Stage') {\n"
                                              + "      steps {\n"
                                              + "        unzip '" + zipFile.getPath() + "'\n"
                                              + "        tektonCreateRaw(inputType: 'FILE', input: '.tekton/task.yaml')\n"
                                              + "      }\n"
                                              + "    }\n"
                                              + "  }\n"
                                              + "}\n", true));

        WorkflowRun b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(1));

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Extracting: .tekton/task.yaml"));
        assertThat(log, containsString("[Pipeline] tektonCreateRaw"));
        assertThat(log, not(containsString(".tekton/task.yaml (No such file or directory)")));
    }

    @Test
    public void testDeclarativePipelineWithYamlInput() throws Exception {
        TaskBuilder taskBuilder = new TaskBuilder()
                .withNewMetadata().withName("testTask").endMetadata();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/tasks")
                .andReturn(HttpURLConnection.HTTP_OK, taskBuilder.build()).once();

        WorkflowJob p = jenkinsRule.jenkins.createProject(WorkflowJob.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setDefinition(new CpsFlowDefinition("pipeline { \n"
                                              + "  agent any\n"
                                              + "  stages {\n"
                                              + "    stage('Stage') {\n"
                                              + "      steps {\n"
                                              + "        unzip '" + zipFile.getPath() + "'\n"
                                              + "        tektonCreateRaw(inputType: 'YAML', input: \"\"\"apiVersion: tekton.dev/v1beta1\n"
                                              + "kind: Task\n"
                                              + "metadata:\n"
                                              + "  name: testTask\n"
                                              + "\"\"\")\n"
                                              + "      }\n"
                                              + "    }\n"
                                              + "  }\n"
                                              + "}\n", true));

        WorkflowRun b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(1));

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Extracting: .tekton/task.yaml"));
        assertThat(log, containsString("[Pipeline] tektonCreateRaw"));
        assertThat(log, not(containsString(".tekton/task.yaml (No such file or directory)")));
    }

    @Test
    public void testFreestyleJobWithFileInput() throws Exception {
        TaskBuilder taskBuilder = new TaskBuilder()
                .withNewMetadata().withName("testTask").endMetadata();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/tasks")
                .andReturn(HttpURLConnection.HTTP_OK, taskBuilder.build()).once();

        FreeStyleProject p = jenkinsRule.jenkins.createProject(FreeStyleProject.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setScm(new ExtractResourceSCM(zipFile));
        p.getBuildersList().add(new CreateRaw(".tekton/task.yaml", "FILE"));

        FreeStyleBuild b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(1));

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Legacy code started this job"));
    }

    @Test
    public void testFreestyleJobWithYamlInput() throws Exception {
        TaskBuilder taskBuilder = new TaskBuilder()
                .withNewMetadata().withName("testTask").endMetadata();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/tasks")
                .andReturn(HttpURLConnection.HTTP_OK, taskBuilder.build()).once();

        FreeStyleProject p = jenkinsRule.jenkins.createProject(FreeStyleProject.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setScm(new ExtractResourceSCM(zipFile));
        p.getBuildersList().add(new CreateRaw("apiVersion: tekton.dev/v1beta1\n"
                                              + "kind: Task\n"
                                              + "metadata:\n"
                                              + "  name: testTask\n", "YAML"));

        FreeStyleBuild b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(1));

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Legacy code started this job"));
    }

    @Test
    public void testFreestyleJobWithComplexYamlInput() throws Exception {
        ToolUtils.getJXPipelineBinary(ToolUtils.class.getClassLoader());

        PipelineRunBuilder pipelineRunBuilder = new PipelineRunBuilder()
                .withNewMetadata()
                    .withName("release")
                    .withNamespace("test")
                    .withUid("pipeline-run-uid")
                .endMetadata()
                .withNewSpec()
                    .withNewPipelineSpec()
                        .addNewTask()
                            .withName("pipelineTaskName")
                        .endTask()
                    .endPipelineSpec()
                .endSpec();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/pipelineruns")
                .andReturn(HttpURLConnection.HTTP_OK, pipelineRunBuilder.build())
                .once();

        TaskRunList taskRunList = new TaskRunListBuilder()
                .addToItems(
                        new TaskRunBuilder()
                            .withNewMetadata()
                                .withName("testTaskRun")
                                .withOwnerReferences(ownerReference("pipeline-run-uid"))
                            .endMetadata()
                        .build())
                .build();

        kubernetesRule.expect()
                .get()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/taskruns?labelSelector=tekton.dev%2FpipelineTask%3DpipelineTaskName%2Ctekton.dev%2FpipelineRun%3Drelease")
                .andReturn(HttpURLConnection.HTTP_OK, taskRunList)
                .once();

        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName("hello-world-pod")
                    .withNamespace("test")
                    .withOwnerReferences(ownerReference("TaskRun","testTaskRun"))
                .endMetadata()
                .withNewSpec()
                    .withContainers(
                        new ContainerBuilder()
                             .withName("hello-world-container")
                        .build()
                    )
                .endSpec()
                .withNewStatus()
                    .withPhase("Succeeded")
                    .withContainerStatuses(
                            new ContainerStatusBuilder()
                                    .withName("hello-world-container")
                                    .withState(
                                            new ContainerStateBuilder()
                                                    .withTerminated(new ContainerStateTerminatedBuilder().withStartedAt("timestamp").build())
                                            .build()
                                    )
                            .build())
                .endStatus()
                .build();

        PodList podList = new PodListBuilder()
                .addToItems(pod)
                .build();

        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods")
                .andReturn(HttpURLConnection.HTTP_OK, podList).once();

        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods/hello-world-pod")
                .andReturn(HttpURLConnection.HTTP_OK, pod).always();
        
        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods/hello-world-pod/log?pretty=false&container=hello-world-container&follow=true")
                .andReturn(HttpURLConnection.HTTP_OK, "Whoop! This is the pod log").once();

        FreeStyleProject p = jenkinsRule.jenkins.createProject(FreeStyleProject.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setScm(new ExtractResourceSCM(zipFile));
        CreateRaw createRaw = new CreateRaw(contents("jx-pipeline.yaml"), "YAML");
        createRaw.setEnableCatalog(true);
        p.getBuildersList().add(createRaw);

        FreeStyleBuild b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Legacy code started this job"));
        assertThat(log, containsString("Whoop! This is the pod log"));

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(8));
    }

    @Test
    public void testFreestyleJobWithExpandedYamlInput() throws Exception {
        ToolUtils.getJXPipelineBinary(ToolUtils.class.getClassLoader());

        PipelineRunBuilder pipelineRunBuilder = new PipelineRunBuilder()
                .withNewMetadata()
                    .withName("release")
                    .withNamespace("test")
                    .withUid("pipeline-run-uid")
                .endMetadata()
                .withNewSpec()
                    .withNewPipelineSpec()
                        .addNewTask()
                            .withName("pipelineTaskName")
                        .endTask()
                    .endPipelineSpec()
                .endSpec();

        kubernetesRule.expect()
                .post()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/pipelineruns")
                .andReturn(HttpURLConnection.HTTP_OK, pipelineRunBuilder.build())
                .once();

        TaskRunList taskRunList = new TaskRunListBuilder()
                .addToItems(
                        new TaskRunBuilder()
                                .withNewMetadata()
                                    .withName("testTaskRun")
                                    .withOwnerReferences(ownerReference("pipeline-run-uid"))
                                .endMetadata()
                                .build())
                .build();

        kubernetesRule.expect()
                .get()
                .withPath("/apis/tekton.dev/v1beta1/namespaces/test/taskruns?labelSelector=tekton.dev%2FpipelineTask%3DpipelineTaskName%2Ctekton.dev%2FpipelineRun%3Drelease")
                .andReturn(HttpURLConnection.HTTP_OK, taskRunList)
                .once();

        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName("hello-world-pod")
                .withNamespace("test")
                .withOwnerReferences(ownerReference("TaskRun","testTaskRun"))
                .endMetadata()
                .withNewSpec()
                .withContainers(
                        new ContainerBuilder()
                                .withName("hello-world-container")
                                .build()
                )
                .endSpec()
                .withNewStatus()
                .withPhase("Succeeded")
                .withContainerStatuses(
                        new ContainerStatusBuilder()
                                .withName("hello-world-container")
                                .withState(
                                        new ContainerStateBuilder()
                                                .withTerminated(new ContainerStateTerminatedBuilder().withStartedAt("timestamp").build())
                                                .build()
                                )
                                .build())
                .endStatus()
                .build();

        PodList podList = new PodListBuilder()
                .addToItems(pod)
                .build();

        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods")
                .andReturn(HttpURLConnection.HTTP_OK, podList).once();

        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods/hello-world-pod")
                .andReturn(HttpURLConnection.HTTP_OK, pod).always();

        kubernetesRule.expect().get().withPath("/api/v1/namespaces/test/pods/hello-world-pod/log?pretty=false&container=hello-world-container&follow=true")
                .andReturn(HttpURLConnection.HTTP_OK, "Whoop! This is the pod log").once();

        FreeStyleProject p = jenkinsRule.jenkins.createProject(FreeStyleProject.class, "p");
        URL zipFile = getClass().getResource("tekton-test-project.zip");
        assertThat(zipFile, is(notNullValue()));

        p.setScm(new ExtractResourceSCM(zipFile));
        CreateRaw createRaw = new CreateRaw(contents("jx-pipeline.expanded.yaml"), "YAML");
        createRaw.setEnableCatalog(false);
        p.getBuildersList().add(createRaw);

        FreeStyleBuild b = jenkinsRule.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0).get());

        String log = jenkinsRule.getLog(b);
        System.out.println(log);

        assertThat(log, containsString("Legacy code started this job"));
        assertThat(log, containsString("Whoop! This is the pod log"));

        assertThat(kubernetesRule.getMockServer().getRequestCount(), is(8));
    }

    private String contents(String filename) throws IOException {
        return IOUtils.toString(this.getClass().getResourceAsStream(filename), StandardCharsets.UTF_8.name());
    }

    private OwnerReference ownerReference(String uid) {
        return new OwnerReference("", false, false, "", "", uid);
    }

    private OwnerReference ownerReference(String kind, String name) {
        return new OwnerReference("", false, false, kind, name, "");
    }
}
