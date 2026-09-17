def getQualityGateStatus(String sonarUrl,
                         String token,
                         String projectKey) {

    def ceTaskId = sh(
        script: """
            curl -s -u ${token}: \
            "${sonarUrl}/api/ce/component?component=${projectKey}" |
            python3 -c "import sys,json; print(json.load(sys.stdin)['current']['id'])"
        """,
        returnStdout: true
    ).trim()

    echo "CE Task ID: ${ceTaskId}"

    timeout(time: 10, unit: 'MINUTES') {

        waitUntil {

            def taskStatus = sh(
                script: """
                    curl -s -u ${token}: \
                    "${sonarUrl}/api/ce/task?id=${ceTaskId}" |
                    python3 -c "import sys,json; print(json.load(sys.stdin)['task']['status'])"
                """,
                returnStdout: true
            ).trim()

            echo "Task Status : ${taskStatus}"

            return taskStatus == "SUCCESS"
        }
    }

    def analysisId = sh(
        script: """
            curl -s -u ${token}: \
            "${sonarUrl}/api/ce/task?id=${ceTaskId}" |
            python3 -c "import sys,json; print(json.load(sys.stdin)['task']['analysisId'])"
        """,
        returnStdout: true
    ).trim()

    return sh(
        script: """
            curl -s -u ${token}: \
            "${sonarUrl}/api/qualitygates/project_status?analysisId=${analysisId}" |
            python3 -c "import sys,json; print(json.load(sys.stdin)['projectStatus']['status'])"
        """,
        returnStdout: true
    ).trim()
}

def call(Map config = [:]) {

pipeline {

    agent any

    environment {
        SONARQUBE_ENV = "sonarqube"
    }

    stages {

        stage('Detect Project Type') {
            steps {
                script {

                    if (fileExists("pom.xml")) {
                        env.PROJECT_TYPE = "java"
                    }
                    else if (fileExists("package.json")) {
                        env.PROJECT_TYPE = "node"
                    }
                    else if (
                        fileExists("requirements.txt") ||
                        fileExists("pyproject.toml") ||
                        fileExists("setup.py")
                    ) {
                        env.PROJECT_TYPE = "python"
                    }
                    else {
                        env.PROJECT_TYPE = "unknown"
                    }

                    echo "Detected Project Type: ${env.PROJECT_TYPE}"
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                script {

                    env.IS_PR_BUILD = env.CHANGE_ID ? "true" : "false"

                    if (!env.CHANGE_ID) {
                        echo "Not a Pull Request build. Skipping Sonar PR validation."
                        return
                    }

                    def repoName = env.GIT_URL
                        .tokenize('/')
                        .last()
                        .replace('.git', '')
                    env.REPO_NAME = repoName

                    echo "Running SonarQube PR analysis"
                    echo "Repository: ${repoName}"
                    echo "PR Number: ${env.CHANGE_ID}"
                    echo "Source Branch: ${env.CHANGE_BRANCH}"
                    echo "Target Branch: ${env.CHANGE_TARGET}"

                    withSonarQubeEnv("${SONARQUBE_ENV}") {

                        if (env.PROJECT_TYPE == "java") {

                            echo "Fetching target branch for Sonar comparison"

                            sh """
                                git fetch origin ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} || true

                                echo "Available branches:"
                                git branch -a

                                echo "Verifying target branch:"
                                git show-ref | grep "${env.CHANGE_TARGET}" || true
                            """


                            echo "Running Java/Maven SonarQube analysis"

                            sh """
                                export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                export PATH="\$JAVA_HOME/bin:\$PATH"

                                /var/lib/jenkins/apache-maven-3.8.8/bin/mvn \
                                  clean verify sonar:sonar \
                                  -Dsonar.host.url="${SONAR_HOST_URL}" \
                                  -Dsonar.token="${SONAR_AUTH_TOKEN}" \
                                  -Dsonar.projectKey="${repoName}" \
                                  -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                  -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                  -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                  -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                            """

                        }
                        else if (env.PROJECT_TYPE == "node") {

                            echo "Running Node.js tests and coverage"

                            def testStatus = sh(
                                script: """
                                    docker run --rm \
                                      -v "\$(pwd):/usr/src" \
                                      -v /opt/yarn-cache:/usr/local/share/.cache/yarn \
                                      -v /opt/jest-cache:/tmp/jest-cache \
                                      -e YARN_CACHE_FOLDER=/usr/local/share/.cache/yarn \
                                      node:22 \
                                      sh -c '
                                          echo "Yarn cache: \$YARN_CACHE_FOLDER"
                                          yarn cache dir
                                          cd /usr/src &&
                                          yarn install --prefer-offline &&
                                          (
                                              npm run test-coverage ||
                                              npm run test:coverage ||
                                              npm run coverage ||
                                              npm test
                                          )
                                      '
                                """,
                                returnStatus: true
                            )

                            echo "Node Test Status: ${testStatus}"

                            if (testStatus != 0) {
                                currentBuild.result = 'UNSTABLE'
                                echo "Tests failed. Continuing with Sonar analysis."
                            }

                            echo "Running Node.js SonarQube analysis"
                            sh """
                            git fetch origin ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} || true
                            git branch -a
                            """

                            sh """
                                docker run --rm \
                                  -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
                                  -e SONAR_TOKEN="${SONAR_AUTH_TOKEN}" \
                                  -v "\$(pwd):/usr/src" \
                                  -v /opt/sonar-cache:/opt/sonar-cache \
                                  sonarsource/sonar-scanner-cli \
                                  -Dsonar.userHome=/opt/sonar-cache \
                                  -Dsonar.scanner.metadataFile=/usr/src/report-task.txt \
                                  -Dsonar.verbose=true \
                                  -Dsonar.projectKey="${repoName}" \
                                  -Dsonar.sources=src \
                                  -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                  -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                  -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                  -Dsonar.tests=src \
                                  -Dsonar.test.inclusions="**/*.spec.ts" \
                                  -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info \
                                  -Dsonar.exclusions="**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*.interface.ts,**/*.enum.ts,**/*.routing.ts,**/*.routes.ts,**/*.spec.ts,**/*.mock.ts,**/*.stub.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,**/assets/**,**/mdo-assets/**,**/themes/**,**/styles/**,**/coverage/**,**/dist/**,**/.angular/**,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js"
                            """

                        }
                        else if (env.PROJECT_TYPE == "python") {

                            sh """
                                git fetch origin ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} || true
                            """

                            echo "Running Python SonarQube analysis"

                            sh """
                                mkdir -p .scannerwork
                                docker run --rm \
                                  -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
                                  -e SONAR_TOKEN="${SONAR_AUTH_TOKEN}" \
                                  -v "\$(pwd):/usr/src" \
                                  sonarsource/sonar-scanner-cli \
                                  -Dsonar.projectKey="${repoName}" \
                                  -Dsonar.sources=. \
                                  -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                  -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                  -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                  -Dsonar.scanner.metadataFile=/usr/src/report-task.txt \
                                  -Dsonar.exclusions="**/.venv/**,**/venv/**,**/__pycache__/**,**/*.pyc" \
                            """

                            sh """
                                echo "Checking report-task.txt"
                                ls -ltr report-task.txt || true
                                cat report-task.txt || true
                            """

                        }
                        else {

                            echo "Running generic SonarQube scan"

                          sh """
                            git fetch origin ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} || true
                            git branch -a
                          """

                            sh """
                                docker run --rm \
                                  -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
                                  -e SONAR_TOKEN="${SONAR_AUTH_TOKEN}" \
                                  -v "\$(pwd):/usr/src" \
                                  sonarsource/sonar-scanner-cli \
                                  -Dsonar.projectKey="${repoName}" \
                                  -Dsonar.sources=. \
                                  -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                  -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                  -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                            """
                        }

                        echo "SonarQube analysis completed successfully"
                    }
                }
            }
        }
        /*
        stage('Quality Gate') {

    when {
        expression {
            env.IS_PR_BUILD == "true"
        }
    }

    steps {

        script {

            withSonarQubeEnv("${SONARQUBE_ENV}") {

                echo "Checking SonarQube Quality Gate"

                def qgStatus = getQualityGateStatus(
                    env.SONAR_HOST_URL,
                    env.SONAR_AUTH_TOKEN,
                    env.REPO_NAME
                )

                echo "Quality Gate Status: ${qgStatus}"

                if (qgStatus != "OK") {

                    error(
                        "SonarQube Quality Gate Failed: ${qgStatus}"
                    )
                }

                echo "SonarQube Quality Gate Passed"
            }
        }
    }
}
        */
        stage('Quality Gate') {

    when {
        expression {
            env.IS_PR_BUILD == "true"
        }
    }

    steps {

        script {

            echo "Checking SonarQube Quality Gate"

            def qgStatus = getQualityGateStatus(
                SONAR_HOST_URL,
                SONAR_AUTH_TOKEN,
                env.REPO_NAME
            )

            echo "Quality Gate Status : ${qgStatus}"

            if (qgStatus != "OK") {

                error(
                    "SonarQube Quality Gate Failed : ${qgStatus}"
                )
            }

            echo "Quality Gate Passed"
        }
    }
}
        stage('Extract Jira Ticket') {
            steps {
                script {

                    def commitMsg = sh(
                        script: "git log -1 --pretty=%B",
                        returnStdout: true
                    ).trim()

                    def matcher = (commitMsg =~ /(KB-\d+)/)

                    if (matcher.find()) {

                        env.JIRA_ID = matcher.group(1)

                        echo "Jira Ticket Found: ${env.JIRA_ID}"

                    }
                    else {

                        env.JIRA_ID = ""

                        echo "No Jira Ticket Found in commit message"
                    }
                }
            }
        }
    }

    post {

        success {
            script {
                echo "PR Validation Successful"
                echo "Manager can now review and merge the PR."
            }
        }

        failure {
            script {
                echo "PR Validation Failed"
                echo "Merge will be blocked by GitHub Branch Protection."
            }
        }

        always {
            echo "PR Validation Pipeline Completed"
        }
    }
}

}
