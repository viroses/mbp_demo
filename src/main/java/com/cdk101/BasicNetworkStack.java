package com.cdk101;

import java.util.*;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.SecretValue;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.AccountRootPrincipal;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.eventsources.*;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.rds.InstanceProps;
import software.amazon.awscdk.services.eks.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.ecr.*;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codepipeline.*;
import software.amazon.awscdk.services.codepipeline.StageProps;
import software.amazon.awscdk.services.codepipeline.actions.*;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.sns.*;
import software.amazon.awscdk.services.cloudwatch.*;
import software.amazon.awscdk.services.cloudwatch.actions.*;

public class BasicNetworkStack extends Stack {

    /**
     * Environment variables for this Demo
     */
    final static public String SLACK_CHANNEL = "";
    final static public String SLACK_WEBHOOK_URL = "";
    final static public String SLACK_VERIFICATION_TOKEN = "";
    final static public String GITHUB_USER_NAME = "";
    final static public String GITHUB_APP_REPO_NAME = "";
    final static public String GITHUB_APP_BRANCH_NAME = "";
    final static public String GITHUB_DB_REPO_NAME = "";
    final static public String GITHUB_DB_BRANCH_NAME = "";
    final static public String GITHUB_PRIVATE_ACCESS_TOKEN = "";

    public BasicNetworkStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public BasicNetworkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        /**
         * VPC and basic network phase
         */
        // Create a new VPC with custom subnets
        final Vpc vpc = Vpc.Builder.create(this, "MBP_VPC")
            .cidr("10.1.0.0/16")
            .maxAzs(2)
            .subnetConfiguration(Arrays.asList(
                SubnetConfiguration.builder().cidrMask(24).name("PublicSubnet").subnetType(SubnetType.PUBLIC).build(),
                SubnetConfiguration.builder().cidrMask(24).name("ApplicationSubnet").subnetType(SubnetType.PRIVATE).build(), 
                SubnetConfiguration.builder().cidrMask(24).name("DatabaseSubnet").subnetType(SubnetType.ISOLATED).build()
            ))
            .build();

        // Create Cloud9 environement #hardcoding
        // Owner ARN에 따라 Cloud9 환경 접속이 불가능하다.
        // OS 선택이 불가능하다.
        // Cloud9 정도는 그냥 만드는게 어떨까 싶다.
        // final CfnEnvironmentEC2 cloud9Env = CfnEnvironmentEC2.Builder.create(this, "MBP_Cloud9")
        //     .instanceType("t3.small")
        //     .name("cloud9 for mbp")
        //     .ownerArn("arn:aws:sts::752115167004:assumed-role/Admin/hyuklee-Isengard") 
        //     .subnetId(vpc.getPublicSubnets().get(0).getSubnetId().toString())
        //     .build();
        
        /**
         * Aurora database phase
         *   : Security group, Parameter group, Cluster parameter group, Aurora Cluster, Route 53
         */

        // Create a security group for Aurora.
        // It allows all internal traffic via 3306 port.
        SecurityGroup auroraSG = new SecurityGroup(this, "aurora-sg", SecurityGroupProps.builder()
            .vpc(vpc)
            .securityGroupName("aurora-mysql-sg")
            .build());
        auroraSG.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(3306),"allows all internal traffic via 3306 port");

        // Create a Cluster Parameter Group. lower_case_table_names is for shopizer application
        ClusterParameterGroup clusterParameterGroup = new ClusterParameterGroup(this, "mbp-mysql-cluster-param", ClusterParameterGroupProps.builder()
            .family("aurora-mysql5.7")
            .description("cluster param for mbp")
            .parameters(new HashMap<String, String>() {{
                put("lower_case_table_names", "1");
                put("character_set_client", "utf8");
                put("character_set_connection", "utf8");
                put("character_set_database", "utf8");
                put("character_set_results", "utf8");
                put("character_set_server", "utf8");
                put("collation_connection", "utf8_general_ci");
                put("collation_server", "utf8_general_ci");
            }})
            .build());
        
        // Create a Parameter Group.
        ParameterGroup parameterGroup = new ParameterGroup(this, "mbp-mysql-param", ParameterGroupProps.builder()
            .family("aurora-mysql5.7")
            .description("param for mbp")
            .build());
        
        // Create Aurora MySQL cluster
        DatabaseCluster aurora = new DatabaseCluster(this, "aurora-mysql", DatabaseClusterProps.builder()
            .engine(DatabaseClusterEngine.AURORA_MYSQL)
            .clusterIdentifier("aurora-mysql")
            .defaultDatabaseName("salesmanager")
            .engineVersion("5.7.12")
            .instanceIdentifierBase("aurora-instance")
            .instanceProps(InstanceProps.builder()
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.SMALL))
                .parameterGroup(parameterGroup)
                .vpc(vpc)
                .securityGroup(auroraSG)
                .vpcSubnets(SubnetSelection.builder()
                    .subnets(vpc.getIsolatedSubnets())
                    .build())
                .build()
            )
            .instances(2)
            .masterUser(Login.builder()
                .username("shopizer")
                .password(SecretValue.plainText("shopizer2020!"))
                .build())
            .parameterGroup(clusterParameterGroup)
            .port(3306)
            .build());

        // Create a private hosted zone in Route53 for handling DB endpoint
        PrivateHostedZone route53PrivateHostedZone = new PrivateHostedZone(this, "route53-db-zone", PrivateHostedZoneProps.builder()
            .vpc(vpc)
            .zoneName("custom-db.com")
            .build());

        // We can encapsule the real DB endpoint and decouple database from application.
        RecordSet dbRecordSetWriter = new RecordSet(this, "custom-db-writer-recordset", RecordSetProps.builder()
            .zone(route53PrivateHostedZone)
            .recordName("mysql-master")
            .recordType(RecordType.CNAME)
            .ttl(Duration.seconds(10))
            .target(RecordTarget.fromValues(aurora.getClusterEndpoint().getHostname()))
            .build());

        RecordSet dbRecordSetReader = new RecordSet(this, "custom-db-reader-recordset", RecordSetProps.builder()
            .zone(route53PrivateHostedZone)
            .recordName("mysql-slave")
            .recordType(RecordType.CNAME)
            .ttl(Duration.seconds(10))
            .target(RecordTarget.fromValues(aurora.getClusterReadEndpoint().getHostname()))
            .build());
        
        /**
         * ChatOps phase
         *   : Alarm notification with Aurora + CloudWatch + Slack
         */

        // Defines a new lambda resource
        final Function hello = Function.Builder.create(this, "HelloHandler")
            .runtime(Runtime.NODEJS_10_X)    // execution environment
            .code(Code.fromAsset("lambda"))  // code loaded from the "lambda" directory
            .handler("hello.handler")        // file is "hello", function is "handler"
            .build();

         // Create API Gateway for ChatOps
        LambdaRestApi apiGateway = new LambdaRestApi(this, "apiGateway", LambdaRestApiProps.builder()
            .restApiName("chatops")
            .handler(hello)
            .proxy(false)
            .endpointConfiguration(EndpointConfiguration.builder().types(Arrays.asList(EndpointType.REGIONAL)).build())
            .build());
        
        // Create SNS Topic for Aurora cluster
        Topic auroraCPUAlarmTopic = new Topic(this, "aurora-cpu-alarm-topic", TopicProps.builder()
            .topicName("auroraCPUAlarmTopic")
            .build());
        
        // Create CloudWatch Alarms: CPU utilization for reader
        Alarm auroraReaderCPUAlarm = new Alarm(this, "aurora-reader-cpu-alarm", AlarmProps.builder()
            .alarmName("aurora-reader-cpu-alarm")
            .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
            .evaluationPeriods(1)
            .threshold(15)
            .metric(Metric.Builder.create()
                .namespace("AWS/RDS")
                .metricName("CPUUtilization")
                .statistic("Average")
                .dimensions(new HashMap<String, Object>() {{
                    put("Role", "READER");
                    put("DBClusterIdentifier", "aurora-mysql");
                 }})
                .period(Duration.minutes(1))
                .build())
            .build());
        
        // Create CloudWatch Alarms: CPU utilization for writer
        Alarm auroraWriterCPUAlarm = new Alarm(this, "aurora-writer-cpu-alarm", AlarmProps.builder()
            .alarmName("aurora-writer-cpu-alarm")
            .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
            .evaluationPeriods(1)
            .threshold(50)
            .metric(Metric.Builder.create()
                .namespace("AWS/RDS")
                .metricName("CPUUtilization")
                .statistic("Average")
                .dimensions(new HashMap<String, Object>() {{
                    put("Role", "WRITER");
                    put("DBClusterIdentifier", "aurora-mysql");
                 }})
                .period(Duration.minutes(1))
                .build())
            .build());
        
        // You can add a notification as below.
        auroraReaderCPUAlarm.addAlarmAction(new SnsAction(auroraCPUAlarmTopic));
        auroraWriterCPUAlarm.addAlarmAction(new SnsAction(auroraCPUAlarmTopic));
        
        // Create an IAM role for Lambda
        Role lambdaRole = new Role(this, "lambda-role", RoleProps.builder()
            .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
            .managedPolicies(Arrays.asList(ManagedPolicy.fromAwsManagedPolicyName("AWSLambdaFullAccess"), 
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodePipelineFullAccess"),
                ManagedPolicy.fromAwsManagedPolicyName("AmazonRDSFullAccess")))
            .build());
        
        // Create Lambda function to send Aurora alarm
        Function auroraNotifierFunc = Function.Builder.create(this, "auroraNotifierFunction")
            .runtime(Runtime.PYTHON_3_6)
            .code(Code.fromAsset("lambda"))
            .handler("auroraNotifier.lambda_handler")
            .environment(new HashMap<String, String>() {{
                put("SLACK_CHANNEL", SLACK_CHANNEL);
                put("SLACK_WEBHOOK_URL", SLACK_WEBHOOK_URL);
            }})
            .role(lambdaRole)
        .build();
        
        // Add trigger for auroraNotifierFunc
        auroraNotifierFunc.addEventSource(new SnsEventSource(auroraCPUAlarmTopic));
        
        /**
         * EKS phase
         * 
         * To access the EKS cluster manually, you need something.
         * When you deploy this CDK app, you will notice that an output will be printed with the update-kubeconfig command.
         * Copy & paste the "aws eks update-kubeconfig ..." command to your shell in order to connect to your EKS cluster with the "masters" role.
         * https://docs.aws.amazon.com/cdk/api/latest/docs/aws-eks-readme.html#masters-role
         */

        // Create an IAM role for EKS
        Role eksctlRole = new Role(this, "eksctl-role", RoleProps.builder()
            .assumedBy(new AccountRootPrincipal())
            .managedPolicies(Arrays.asList(ManagedPolicy.fromAwsManagedPolicyName("AdministratorAccess")))
            .build());
        
        // Create an EKS Cluster
        Cluster eksCluster = new Cluster(this, "eks-mbp-cluster", ClusterProps.builder()
            .clusterName("mbp-eksctl")
            .defaultCapacity(0)
            .kubectlEnabled(true)
            .mastersRole(eksctlRole)
            .vpc(vpc)
            .build());
        
        // Add capacity to the EKS cluster
        eksCluster.addNodegroup("eks-custom-nodegroup-1", NodegroupOptions.builder()
            .instanceType(InstanceType.of(InstanceClass.COMPUTE5, InstanceSize.LARGE))
            .desiredSize(6)
            .minSize(2)
            .maxSize(20)
            .build());

        // Add metrics-server for HPA(Horizontal Pod AutoScaler) using Helm Chart
        eksCluster.addChart("eks-helm-chart-metrics-server", HelmChartOptions.builder()
            // .namespace("metrics")
            .chart("metrics-server")
            .repository("https://kubernetes-charts.storage.googleapis.com/")
            .build());
        
        /**
         * ChatOps phase
         *   : Manual approval via Slack
         */
        
        // Create a SNS Topic for EKS CodePipeline
        Topic manualApprovalTopic = new Topic(this, "manual-approval-topic", TopicProps.builder()
            .topicName("manualApprovalTopic")
            .build());

        // Create a Lambda functions for approval requester and handler.
        // For CI/CD, Lambda code should be in CodeCommit or Git. This is for PoC.
        Function approvalRequesterFunc = Function.Builder.create(this, "approvalRequesterFunction")
            .runtime(Runtime.PYTHON_3_6)
            .code(Code.fromAsset("lambda"))
            .handler("approvalRequester.lambda_handler")
            .environment(new HashMap<String, String>() {{
                put("SLACK_CHANNEL", SLACK_CHANNEL);
                put("SLACK_WEBHOOK_URL", SLACK_WEBHOOK_URL);
            }})
            .role(lambdaRole)
            .build();
        
        // You can add a trigger in Lambda as below.
        approvalRequesterFunc.addEventSource(new SnsEventSource(manualApprovalTopic));
        
        Function approvalHandlerFunc = Function.Builder.create(this, "approvalHandlerFunction")
            .runtime(Runtime.PYTHON_3_6)
            .code(Code.fromAsset("lambda"))
            .handler("approvalHandler.lambda_handler")
            .environment(new HashMap<String, String>() {{
                put("SLACK_VERIFICATION_TOKEN", SLACK_VERIFICATION_TOKEN);
            }})
            .role(lambdaRole)
            .build();
            
        // Create an APPTOVAL API for approvalHandlerFunc
        apiGateway.getRoot()
            .addResource("APPROVAL")
            .addMethod("POST", new LambdaIntegration(approvalHandlerFunc, LambdaIntegrationOptions.builder()
                .proxy(true)
                .build()));
        
        /**
         * CI/CD phase
         *   : Application( GitHub + CodePipeline + CodeBuild )
         */

        // Create an ECR repo
        Repository ecrRepository = new Repository(this, "ecr-repo", RepositoryProps.builder()
            .repositoryName("mbp-ecr-repo")
            .build());

        Repository ecrTestRepository = new Repository(this, "ecr-test-repo", RepositoryProps.builder()
            .repositoryName("test-ecr-repo")
            .build());
        
        // Environment variables for CodeBuild
        Map<String,BuildEnvironmentVariable> buildEnvironmentVariables = new HashMap<String,BuildEnvironmentVariable>();
        buildEnvironmentVariables.put("EKS_CLUSTER_NAME", BuildEnvironmentVariable.builder().value(eksCluster.getClusterName()).build());
        // buildEnvironmentVariables.put("EKS_CLUSTER_NAME", BuildEnvironmentVariable.builder().value("mbp-eksctl").build());
        buildEnvironmentVariables.put("EKS_KUBECTL_ROLE_ARN", BuildEnvironmentVariable.builder().value(eksctlRole.getRoleArn()).build());
        buildEnvironmentVariables.put("REPOSITORY_URI", BuildEnvironmentVariable.builder().value(ecrRepository.getRepositoryUri()).build());
        buildEnvironmentVariables.put("REPOSITORY_BRANCH", BuildEnvironmentVariable.builder().value(GITHUB_APP_BRANCH_NAME).build());
        buildEnvironmentVariables.put("REPOSITORY_NAME", BuildEnvironmentVariable.builder().value(GITHUB_APP_REPO_NAME).build());
        
        // Create CodeBuild for EKS
        PipelineProject eksPipelineProject = new PipelineProject(this, "eks-codebuild-project", PipelineProjectProps.builder()
            .projectName("eks-codebuild-project")
            .vpc(vpc)
            .subnetSelection(SubnetSelection.builder()
                .subnets(vpc.getPrivateSubnets())
                .build())
            .environment(BuildEnvironment.builder()
                .buildImage(LinuxBuildImage.STANDARD_3_0)
                .environmentVariables(buildEnvironmentVariables)
                .privileged(true)
                .build())
            .build());
        
        // Give eksPipelineProject's role Admin permission
        eksPipelineProject.getRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AdministratorAccess"));
        
        // These are for CodePipeline
        Artifact githubSourceOutput = new Artifact();
        Artifact codebuildOutput = new Artifact("codebuildOutput");
        
        // Create CodePipeline for EKS
        Pipeline.Builder.create(this, "mpb-eks-pipeline")
            .stages(Arrays.asList(
                StageProps.builder()
                    .stageName("Source")
                    .actions(Arrays.asList(GitHubSourceAction.Builder.create()
                        .actionName("github-source")
                        .owner(GITHUB_USER_NAME)
                        .repo(GITHUB_APP_REPO_NAME)
                        .branch(GITHUB_APP_BRANCH_NAME)
                        .oauthToken(SecretValue.plainText(GITHUB_PRIVATE_ACCESS_TOKEN))
                        .output(githubSourceOutput)
                        .build()))
                    .build(),
                StageProps.builder()
                    .stageName("Approval")
                    .actions(Arrays.asList(ManualApprovalAction.Builder.create()
                        .actionName("manual-approval")
                        .notificationTopic(manualApprovalTopic)
                        .build()))
                    .build(),
                StageProps.builder()
                    .stageName("Build")
                    .actions(Arrays.asList(CodeBuildAction.Builder.create()
                        .actionName("codebuild-build")
                        .project(eksPipelineProject)
                        .input(githubSourceOutput)
                        .outputs(Arrays.asList(codebuildOutput))
                        .build()))
                    .build()))
            .build();
        
        /**
         * CI/CD phase
         *   : Database(CodePipeline + GitHub + CodeBuild)
         */ 
        
        // Create CodeBuild for Database
        PipelineProject dbPipelineProject = new PipelineProject(this, "db-codebuild-project", PipelineProjectProps.builder()
            .projectName("db-codebuild-project")
            .vpc(vpc)
            .subnetSelection(SubnetSelection.builder()
                .subnets(vpc.getPrivateSubnets())
                .build())
            .environment(BuildEnvironment.builder()
                .buildImage(LinuxBuildImage.STANDARD_3_0)
                .privileged(true)
                .build())
            .build());
        
        // Give eksPipelineProject's role Admin permission
        dbPipelineProject.getRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AdministratorAccess"));
        
        // These are for CodePipeline
        Artifact dbSourceOutput = new Artifact();
        Artifact dbCodebuildOutput = new Artifact("codebuildOutput");

        // Environment variables for CodeBuild
        Map<String,BuildEnvironmentVariable> dbBuildEnvironmentVariables = new HashMap<String,BuildEnvironmentVariable>();
        dbBuildEnvironmentVariables.put("PRIVATE_ACCESS_TOKEN", BuildEnvironmentVariable.builder().value(GITHUB_PRIVATE_ACCESS_TOKEN).build());
        dbBuildEnvironmentVariables.put("COMMIT_ID", BuildEnvironmentVariable.builder().value("#{SourceVariables.CommitId}").build());
        
        // Create CodePipeline for Database
        Pipeline.Builder.create(this, "mpb-db-pipeline")
            .stages(Arrays.asList(
                StageProps.builder()
                    .stageName("Source")
                    .actions(Arrays.asList(GitHubSourceAction.Builder.create()
                        .actionName("github-source")
                        .owner(GITHUB_USER_NAME)
                        .repo(GITHUB_DB_REPO_NAME)
                        .branch(GITHUB_DB_BRANCH_NAME)
                        .variablesNamespace("SourceVariables")
                        .oauthToken(SecretValue.plainText(GITHUB_PRIVATE_ACCESS_TOKEN))
                        .output(dbSourceOutput)
                        .build()))
                    .build(),
                StageProps.builder()
                    .stageName("Build")
                    .actions(Arrays.asList(CodeBuildAction.Builder.create()
                        .actionName("codebuild-build")
                        .project(dbPipelineProject)
                        .input(dbSourceOutput)
                        .outputs(Arrays.asList(dbCodebuildOutput))
                        .environmentVariables(dbBuildEnvironmentVariables)
                        .build()))
                    .build()))
            .build();
        
        /**
         * ChatOps phase
         *   : Interating with Slack slash command
         */ 

        // Create a Lambda function to handle slash command
        Function slashCommanderFunc = Function.Builder.create(this, "slashCommanderFunction")
            .runtime(Runtime.PYTHON_3_6)
            .code(Code.fromAsset("lambda"))
            .handler("slashCommander.lambda_handler")
            .role(lambdaRole)
            .build();
        
        // Create an SLASH API for slashCommanderFunc
        apiGateway.getRoot()
            .addResource("SLASH")
            .addMethod("POST", new LambdaIntegration(slashCommanderFunc, LambdaIntegrationOptions.builder()
                .proxy(true)
                .build()));
    }
}