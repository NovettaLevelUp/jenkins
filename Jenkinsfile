#!/usr/bin/env groovy

/*
 * This Jenkinsfile is intended to run on https://ci.jenkins.io and may fail anywhere else.
 * It makes assumptions about plugins being installed, labels mapping to nodes that can build what is needed, etc.
 */

def buildNumber = BUILD_NUMBER as int; if (buildNumber > 1) milestone(buildNumber - 1); milestone(buildNumber) // JENKINS-43353 / JENKINS-58625

def failFast = false
// Same memory sizing for both builds and ATH
def javaOpts = ["JAVA_OPTS=-Xmx1536m -Xms512m","MAVEN_OPTS=-Xmx1536m -Xms512m"]

properties([
    buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '3')),
    disableConcurrentBuilds(abortPrevious: true)
])

def buildTypes = ['Linux', 'Windows']
def jdks = [8, 11]

def builds = [:]
for(i = 0; i < buildTypes.size(); i++) {
for(j = 0; j < jdks.size(); j++) {
    def buildType = buildTypes[i]
    def jdk = jdks[j]
    if (buildType == 'Windows' && jdk == 8) {
        continue // unnecessary use of hardware
    }
    builds["${buildType}-jdk${jdk}"] = {
        // see https://github.com/jenkins-infra/documentation/blob/master/ci.adoc#node-labels for information on what node types are available
        String agentContainerLabel = jdk == 8 ? 'maven' : 'maven-11'
        if (buildType == 'Windows') {
            agentContainerLabel += '-windows'
        }
        node(agentContainerLabel) {
                // First stage is actually checking out the source. Since we're using Multibranch
                // currently, we can use "checkout scm".
                stage('Checkout') {
                    checkout scm
                }

                def changelistF = "${pwd tmp: true}/changelist"
                def m2repo = "${pwd tmp: true}/m2repo"

                // Now run the actual build.
                stage("${buildType} Build / Test") {
                    timeout(time: 300, unit: 'MINUTES') {
                      realtimeJUnit(healthScaleFactor: 20.0, testResults: '*/target/surefire-reports/*.xml,war/junit.xml') {
                        // -Dmaven.repo.local=… tells Maven to create a subdir in the temporary directory for the local Maven repository
                        // -ntp requires Maven >= 3.6.1
                        def mvnCmd = "mvn -Pdebug -Pjapicmp -U -Dset.changelist help:evaluate -Dexpression=changelist -Doutput=$changelistF clean install -Dmaven.test.failure.ignore -V -B -ntp -Dmaven.repo.local=$m2repo -Dspotbugs.failOnError=false -Dcheckstyle.failOnViolation=false -e"
                        infra.runWithMaven(mvnCmd, jdk.toString(), javaOpts, true)

                        if(isUnix()) {
                            sh 'git add . && git diff --exit-code HEAD'
                        }
                      }
                    }
                }

                // Once we've built, archive the artifacts and the test results.
                stage("${buildType} Publishing") {
                        archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/surefire-reports/*.dumpstream'
                        if (! fileExists('core/target/surefire-reports/TEST-jenkins.Junit4TestsRanTest.xml') ) {
                            error 'junit 4 tests are no longer being run for the core package'
                        }
                        if (! fileExists('test/target/surefire-reports/TEST-jenkins.Junit4TestsRanTest.xml') ) {
                            error 'junit 4 tests are no longer being run for the test package'
                        } // cli has been migrated to junit 5
                        if (failFast && currentBuild.result == 'UNSTABLE') {
                            error 'There were test failures; halting early'
                        }
                    if (buildType == 'Linux' && jdk == jdks[0]) {
                        def folders = env.JOB_NAME.split('/')
                        if (folders.length > 1) {
                            discoverGitReferenceBuild(scm: folders[1])
                        }

                        echo "Recording static analysis results for '${buildType}'"
                        recordIssues enabledForFailure: true,
                                tools: [java(), javaDoc()],
                                filters: [excludeFile('.*Assert.java')],
                                sourceCodeEncoding: 'UTF-8',
                                skipBlames: true,
                                trendChartType: 'TOOLS_ONLY'
                        recordIssues([tool: spotBugs(pattern: '**/target/spotbugsXml.xml'),
                                                 sourceCodeEncoding: 'UTF-8',
                                                 skipBlames: true,
                                                 trendChartType: 'TOOLS_ONLY',
                                                 qualityGates: [[threshold: 1, type: 'NEW', unstable: true]]])
                        recordIssues([tool: checkStyle(pattern: '**/target/checkstyle-result.xml'),
                                                   sourceCodeEncoding: 'UTF-8',
                                                   skipBlames: true,
                                                   trendChartType: 'TOOLS_ONLY',
                                                   qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]]])
                        if (failFast && currentBuild.result == 'UNSTABLE') {
                            error 'Static analysis quality gates not passed; halting early'
                        }

                        def changelist = readFile(changelistF)
                        dir(m2repo) {
                            archiveArtifacts artifacts: "**/*$changelist/*$changelist*",
                                             excludes: '**/*.lastUpdated,**/jenkins-test*/',
                                             allowEmptyArchive: true, // in case we forgot to reincrementalify
                                             fingerprint: true
                        }
                        publishHTML([allowMissing: true, alwaysLinkToLastBuild: false, includes: 'japicmp.html', keepAll: false, reportDir: 'core/target/japicmp', reportFiles: 'japicmp.html', reportName: 'API compatibility', reportTitles: 'japicmp report'])
                    }
                }
        }
    }
}}

builds.ath = {
    node("docker-highmem") {
        // Just to be safe
        deleteDir()
        def fileUri
        def metadataPath
        dir("sources") {
            checkout scm
            def mvnCmd = 'mvn --batch-mode --show-version -ntp -Pquick-build -am -pl war package -Dmaven.repo.local=$WORKSPACE_TMP/m2repo'
            infra.runWithMaven(mvnCmd, "11", javaOpts, true)
            dir("war/target") {
                fileUri = "file://" + pwd() + "/jenkins.war"
            }
            metadataPath = pwd() + "/essentials.yml"
        }
        dir("ath") {
            runATH jenkins: fileUri, metadataFile: metadataPath
        }
    }
}

builds.failFast = failFast
parallel builds
infra.maybePublishIncrementals()
