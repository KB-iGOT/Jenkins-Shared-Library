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
                        } else if (fileExists("package.json")) {
                            env.PROJECT_TYPE = "node"
                        } else if (
                            fileExists("requirements.txt") ||
                            fileExists("pyproject.toml") ||
                            fileExists("setup.py")
                        ) {
                            env.PROJECT_TYPE = "python"
                        } else {
                            env.PROJECT_TYPE = "unknown"
                        }

                        echo "Detected Project Type: ${env.PROJECT_TYPE}"
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
                        } else {
                            env.JIRA_ID = ""
                            error(
                                "Jira Ticket is mandatory. Commit message must " +
                                "contain a valid Jira ID in the format KB-1234."
                            )
                        }
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
                            } else if (env.PROJECT_TYPE == "node") {
                                echo "Running Node.js tests and coverage"

                                def testStatus = sh(
                                    script: """
                                        docker run --rm \
                                          -v "\$(pwd):/usr/src" \
                                          -v /opt/jest-cache:/tmp/jest-cache \
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

                                def scannerHome = tool 'sonar-scanner'

                                sh """
                                    rm -rf .scannerwork || true
                                    mkdir -p .scannerwork

                                    export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                    export PATH=\$JAVA_HOME/bin:\$PATH

                                    java -version

                                    ${scannerHome}/bin/sonar-scanner \
                                      -Dsonar.scanner.skipJreProvisioning=true \
                                      -Dsonar.host.url="${SONAR_HOST_URL}" \
                                      -Dsonar.token="${SONAR_AUTH_TOKEN}" \
                                      -Dsonar.projectKey="${repoName}" \
                                      -Dsonar.sources=src \
                                      -Dsonar.tests=. \
                                      -Dsonar.userHome=/opt/sonar-cache \
                                      -Dsonar.verbose=true \
                                      -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                      -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                      -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                      -Dsonar.test.inclusions=**/*.spec.ts \
                                      -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info \
                                      -Dsonar.exclusions="**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*.interface.ts,**/*.enum.ts,**/*.routing.ts,**/*.routes.ts,**/*.spec.ts,**/*.mock.ts,**/*.stub.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,**/assets/**,**/mdo-assets/**,**/themes/**,**/styles/**,**/coverage/**,**/dist/**,**/.angular/**,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js"
                                """
                            } else if (env.PROJECT_TYPE == "python") {
                                sh """
                                    git fetch origin ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} || true
                                """

                                echo "Running Python SonarQube analysis"

                                def scannerHome = tool 'sonar-scanner'

                                sh """
                                    export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                    export PATH=\$JAVA_HOME/bin:\$PATH

                                    java -version

                                    ${scannerHome}/bin/sonar-scanner \
                                      -Dsonar.scanner.skipJreProvisioning=true \
                                      -Dsonar.host.url="${SONAR_HOST_URL}" \
                                      -Dsonar.token="${SONAR_AUTH_TOKEN}" \
                                      -Dsonar.projectKey="${repoName}" \
                                      -Dsonar.sources=. \
                                      -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                      -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                      -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                      -Dsonar.scanner.metadataFile=/usr/src/report-task.txt \
                                      -Dsonar.exclusions="**/.venv/**,**/venv/**,**/__pycache__/**,**/*.pyc"
                                """
                            } else {
                                echo "Running generic SonarQube scan"

                                def scannerHome = tool 'sonar-scanner'

                                sh """
                                    export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                    export PATH=\$JAVA_HOME/bin:\$PATH

                                    java -version

                                    ${scannerHome}/bin/sonar-scanner \
                                      -Dsonar.scanner.skipJreProvisioning=true \
                                      -Dsonar.host.url="${SONAR_HOST_URL}" \
                                      -Dsonar.token="${SONAR_AUTH_TOKEN}" \
                                      -Dsonar.projectKey="${repoName}" \
                                      -Dsonar.sources=. \
                                      -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                      -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                      -Dsonar.pullrequest.base="${env.CHANGE_TARGET}"
                                """

                                sh """
                                    echo "Checking report-task.txt"
                                    ls -ltr report-task.txt || true
                                    cat report-task.txt || true
                                """
                            }

                            echo "SonarQube analysis completed successfully"
                        }
                    }
                }
            }

            /*
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
            */

            stage('Quality Gate') {
                when {
                    expression {
                        env.IS_PR_BUILD == "true"
                    }
                }

                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            echo "Waiting for SonarQube Quality Gate..."

                            def qg = waitForQualityGate(
                                abortPipeline: false
                            )

                            echo "Quality Gate Status: ${qg.status}"

                            def repoName = env.GIT_URL
                                .tokenize('/')
                                .last()
                                .replace('.git', '')

                            def sonarDashboardUrl =
                                "https://dev.karmayogibharat.net/codeanalysis/dashboard" +
                                "?id=${repoName}&pullRequest=${env.CHANGE_ID}"

                            def githubState = qg.status == 'OK' ? 'success' : 'failure'

                            def githubDescription = qg.status == 'OK' ?
                                'SonarQube Quality Gate Passed' :
                                "SonarQube Quality Gate Failed: ${qg.status}"

                            withCredentials([
                                usernamePassword(
                                    credentialsId: 'github-cred',
                                    usernameVariable: 'GITHUB_USER',
                                    passwordVariable: 'GITHUB_TOKEN'
                                )
                            ]) {
                                sh """
                                    curl --fail-with-body \
                                      --request POST \
                                      --header "Accept: application/vnd.github+json" \
                                      --header "Authorization: Bearer \$GITHUB_TOKEN" \
                                      --header "X-GitHub-Api-Version: 2022-11-28" \
                                      "https://api.github.com/repos/KB-iGOT/${repoName}/statuses/${env.GIT_COMMIT}" \
                                      --data '{
                                        "state": "${githubState}",
                                        "target_url": "${sonarDashboardUrl}",
                                        "description": "${githubDescription}",
                                        "context": "sonarqube/quality-gate"
                                      }'
                                """
                            }

                            if (qg.status != 'OK') {
                                error(
                                    "SonarQube Quality Gate Failed: ${qg.status}"
                                )
                            }

                            echo "SonarQube Quality Gate Passed"
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
