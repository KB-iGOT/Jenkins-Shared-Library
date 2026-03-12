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
                        else if (fileExists("requirements.txt") || fileExists("pyproject.toml") || fileExists("setup.py")) {
                            env.PROJECT_TYPE = "python"
                        }
                        else {
                            env.PROJECT_TYPE = "unknown"
                        }

                        echo "📦 Detected Project Type: ${env.PROJECT_TYPE}"
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {

                        def repoName = env.GIT_URL.tokenize('/').last().replace('.git','')

                        echo "🔎 Running SonarQube PR analysis for repo: ${repoName}"

                        withSonarQubeEnv("${SONARQUBE_ENV}") {

                            if (env.PROJECT_TYPE == "java") {

                                sh """
                                mvn clean verify sonar:sonar \
                                  -Dsonar.projectKey=${repoName} \
                                  -Dsonar.pullrequest.key=${CHANGE_ID} \
                                  -Dsonar.pullrequest.branch=${CHANGE_BRANCH} \
                                  -Dsonar.pullrequest.base=${CHANGE_TARGET}
                                """

                            } else if (env.PROJECT_TYPE == "node") {

                                sh """
                                docker run --rm -v "${PWD}:/usr/src" node:22 sh -c "cd /usr/src && yarn && npm run test-coverage" || \
                                docker run \
                                  --rm \
                                  -e SONAR_HOST_URL="${sonar_host}" \
                                  -e SONAR_LOGIN=${sonar_token} \
                                  -v "${PWD}:/usr/src" \
                                  sonarsource/sonar-scanner-cli \
                                    -Dsonar.projectKey=${repoName} \
                                    -Dsonar.sources=. \
                                    -Dsonar.pullrequest.key=${CHANGE_ID} \
                                    -Dsonar.pullrequest.branch=${CHANGE_BRANCH} \
                                    -Dsonar.pullrequest.base=${CHANGE_TARGET} \
                                    -Dsonar.exclusions=**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js \
                                    -Dsonar.tests=src \
                                    -Dsonar.test.inclusions="**/*.spec.ts" \
                                    -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info
                                """

                            }

                            else {

                              echo "⚠️ Running generic Sonar scan"
                              sh """
                              docker run --rm \
                                -e SONAR_HOST_URL=${sonar_host} \
                                -e SONAR_LOGIN=${sonar_token} \
                                -v "\$(pwd):/usr/src" \
                                sonarsource/sonar-scanner-cli \
                                  -Dsonar.projectKey=${repoName} \
                                  -Dsonar.sources=. \
                                  -Dsonar.pullrequest.key=${CHANGE_ID} \
                                  -Dsonar.pullrequest.branch=${CHANGE_BRANCH} \
                                  -Dsonar.pullrequest.base=${CHANGE_TARGET}
                              """
                            }                        
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    script {

                        echo "⏳ Waiting for SonarQube Quality Gate"

                        timeout(time: 10, unit: 'MINUTES') {

                            def qg = waitForQualityGate()

                            echo "🔎 Quality Gate Status: ${qg.status}"

                            if (qg.status != 'OK') {
                                error("❌ Quality Gate Failed")
                            }

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

                        def matcher = (commitMsg =~ /(KB-\\d+)/)

                        if (matcher.find()) {

                            env.JIRA_ID = matcher.group(1)

                            echo "🎫 Jira Ticket Found: ${env.JIRA_ID}"

                        } else {

                            env.JIRA_ID = ""

                            echo "ℹ No Jira Ticket Found in commit message"

                        }

                    }
                }
            }

        }

        post {

            success {

                script {
                    echo "✅ PR Validation Successful"
                    echo "Manager can now review and merge the PR."
                }

            }

            failure {

                script {
                    echo "❌ PR Validation Failed"
                    echo "Merge will be blocked by GitHub Branch Protection."
                }

            }

            always {
                echo "🏁 PR Validation Pipeline Completed"
            }

        }

    }

}
