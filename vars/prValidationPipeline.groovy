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

                            sh """
                                docker run --rm \
                                  -v "\$(pwd):/usr/src" \
                                  node:22 \
                                  sh -c "cd /usr/src && yarn && npm run test-coverage"
                            """

                            echo "Running Node.js SonarQube analysis"

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
                                  -Dsonar.exclusions="**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js" \
                                  -Dsonar.tests=src \
                                  -Dsonar.test.inclusions="**/*.spec.ts" \
                                  -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info \
                                  -Dsonar.working.directory=/usr/src/.scannerwork
                            """

                        }
                        else if (env.PROJECT_TYPE == "python") {

                            echo "Running Python SonarQube analysis"

                            sh """
                                mkdir -p .scannerwork
                                mkdir -p .sonar-cache
                                chmod -R 777 .sonar-cache
                                docker run --rm \
                                  -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
                                  -e SONAR_TOKEN="${SONAR_AUTH_TOKEN}" \
                                  -v "\$(pwd):/usr/src" \
                                  -v "\$(pwd)/.sonar-cache:/opt/sonar-scanner/.sonar/cache" \
                                  sonarsource/sonar-scanner-cli \
                                  -Dsonar.projectKey="${repoName}" \
                                  -Dsonar.sources=. \
                                  -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                  -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                  -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                  -Dsonar.exclusions="**/.venv/**,**/venv/**,**/__pycache__/**,**/*.pyc" \
                            """

                        }
                        else {

                            echo "Running generic SonarQube scan"

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
                                  -Dsonar.working.directory=/usr/src/.scannerwork
                            """
                        }

                        echo "SonarQube analysis completed successfully"
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                script {

                    if (env.IS_PR_BUILD != "true") {
                        echo "Skipping Quality Gate for non-PR build."
                        return
                    }

                    echo "Waiting for SonarQube Quality Gate"

                    timeout(time: 10, unit: 'MINUTES') {

                        def qg = waitForQualityGate(
                            abortPipeline: false
                        )

                        echo "Quality Gate Status: ${qg.status}"

                        if (qg.status != 'OK') {
                            error("SonarQube Quality Gate Failed: ${qg.status}")
                        }

                        echo "SonarQube Quality Gate Passed"
                    }
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
