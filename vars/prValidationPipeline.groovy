def call(Map config = [:]) {

    /*
     * ---------------------------------------------------------
     * Update Jenkins PR validation status on GitHub
     * ---------------------------------------------------------
     *
     * GitHub status:
     *   context    : jenkins/pr-validation
     *   target_url : Jenkins BUILD_URL
     *
     * States:
     *   pending
     *   success
     *   failure
     */
    def updateJenkinsGitHubStatus = { String state, String description ->

        def repoName = env.GIT_URL
            .tokenize('/')
            .last()
            .replace('.git', '')

        echo "Updating Jenkins GitHub status: ${state}"

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
                    "state": "${state}",
                    "target_url": "${env.BUILD_URL}",
                    "description": "${description}",
                    "context": "jenkins/pr-validation"
                  }'
            """
        }
    }

    pipeline {

        agent any

        environment {
            SONARQUBE_ENV = "sonarqube"
        }

        stages {

            /*
             * ---------------------------------------------------------
             * Initialize PR validation
             * ---------------------------------------------------------
             */
            stage('Initialize PR Validation') {

                when {
                    expression {
                        env.CHANGE_ID
                    }
                }

                steps {
                    script {

                        updateJenkinsGitHubStatus(
                            'pending',
                            'Jenkins PR Validation Running'
                        )

                        echo "Jenkins Build URL: ${env.BUILD_URL}"
                    }
                }
            }

            /*
             * ---------------------------------------------------------
             * Detect Project Type
             * ---------------------------------------------------------
             */
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

            /*
             * ---------------------------------------------------------
             * Extract Jira Ticket
             * ---------------------------------------------------------
             */
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

            /*
             * ---------------------------------------------------------
             * SonarQube Analysis
             * ---------------------------------------------------------
             */
            stage('SonarQube Analysis') {

                steps {

                    script {

                        env.IS_PR_BUILD =
                            env.CHANGE_ID ? "true" : "false"

                        /*
                         * Perform Sonar PR validation only for PR builds.
                         */
                        if (!env.CHANGE_ID) {

                            echo(
                                "Not a Pull Request build. " +
                                "Skipping Sonar PR validation."
                            )

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

                            /*
                             * =================================================
                             * JAVA
                             * =================================================
                             */
                            if (env.PROJECT_TYPE == "java") {

                                echo "Running Java tests changed in this PR"

                                /*
                                 * Fetch target branch.
                                 */
                                sh """
                                    git fetch origin \
                                      ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} \
                                      || true

                                    echo "PR Target Branch: ${env.CHANGE_TARGET}"

                                    echo "Changed files in this PR:"

                                    git diff \
                                      --name-only \
                                      ${env.CHANGE_TARGET}...HEAD
                                """

                                /*
                                 * Detect Java unit-test files added or
                                 * modified by this PR.
                                 *
                                 * Supported:
                                 *   *Test.java
                                 *   *Tests.java
                                 *   *TestCase.java
                                 */
                                def changedJavaTestFiles = sh(
                                    script: """
                                        git diff \
                                          --name-only \
                                          --diff-filter=AM \
                                          ${env.CHANGE_TARGET}...HEAD \
                                        | grep -E '(^|/)src/test/java/.*(Test|Tests|TestCase)\\.java\$' \
                                        || true
                                    """,
                                    returnStdout: true
                                ).trim()

                                /*
                                 * Fail if PR contains no added/modified tests.
                                 */
                                if (!changedJavaTestFiles) {

                                    error(
                                        "No Java unit test files were added or " +
                                        "modified in this PR. " +
                                        "Developer must add/update unit tests " +
                                        "for the new code."
                                    )
                                }

                                echo "Java test files changed in this PR:"
                                echo "${changedJavaTestFiles}"

                                /*
                                 * Convert:
                                 *
                                 * src/test/java/com/example/UserServiceTest.java
                                 *
                                 * to:
                                 *
                                 * com.example.UserServiceTest
                                 */
                                def javaTestClasses = changedJavaTestFiles
                                    .split('\n')
                                    .collect {
                                        it.trim()
                                            .replaceFirst(
                                                '^.*/src/test/java/',
                                                ''
                                            )
                                            .replaceFirst(
                                                '\\.java$',
                                                ''
                                            )
                                            .replaceAll(
                                                '/',
                                                '.'
                                            )
                                    }
                                    .findAll {
                                        it
                                    }
                                    .join(',')

                                echo "Java tests selected for execution:"
                                echo "${javaTestClasses}"

                                /*
                                 * Run only Java tests modified/added
                                 * by this PR.
                                 */
                                def javaTestStatus = sh(
                                    script: """
                                        export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                        export PATH="\$JAVA_HOME/bin:\$PATH"

                                        java -version

                                        /var/lib/jenkins/apache-maven-3.8.8/bin/mvn \
                                          clean test \
                                          -Dtest="${javaTestClasses}"
                                    """,
                                    returnStatus: true
                                )

                                echo(
                                    "Changed Java test files status: " +
                                    "${javaTestStatus}"
                                )

                                if (javaTestStatus != 0) {

                                    error(
                                        "Java unit tests added/modified in " +
                                        "this PR failed. Developer needs to " +
                                        "fix the affected tests/code."
                                    )
                                }

                                echo(
                                    "PR Java unit tests passed successfully"
                                )

                                /*
                                 * Maven verify + JaCoCo + Sonar.
                                 *
                                 * Keep -Dtest so only selected PR test
                                 * classes execute.
                                 */
                                echo(
                                    "Running Java/Maven SonarQube analysis"
                                )

                                sh """
                                    export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                    export PATH="\$JAVA_HOME/bin:\$PATH"

                                    /var/lib/jenkins/apache-maven-3.8.8/bin/mvn \
                                      verify sonar:sonar \
                                      -Dtest="${javaTestClasses}" \
                                      -Dsonar.host.url="${SONAR_HOST_URL}" \
                                      -Dsonar.token="${SONAR_AUTH_TOKEN}" \
                                      -Dsonar.projectKey="${repoName}" \
                                      -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                      -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                      -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                      -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                                """

                                echo(
                                    "Java SonarQube analysis " +
                                    "completed successfully"
                                )

                            /*
                             * =================================================
                             * NODE.JS
                             * =================================================
                             */
                            } else if (env.PROJECT_TYPE == "node") {

                                echo(
                                    "Running Node.js tests changed in this PR"
                                )

                                /*
                                 * Fetch target branch.
                                 */
                                sh """
                                    git fetch origin \
                                      ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} \
                                      || true

                                    echo "PR Target Branch: ${env.CHANGE_TARGET}"

                                    echo "Changed files in this PR:"

                                    git diff \
                                      --name-only \
                                      ${env.CHANGE_TARGET}...HEAD
                                """

                                /*
                                 * Get only test files added or modified
                                 * by this PR.
                                 */
                                def changedTestFiles = sh(
                                    script: """
                                        git diff \
                                          --name-only \
                                          --diff-filter=AM \
                                          ${env.CHANGE_TARGET}...HEAD \
                                        | grep -E '\\.(spec|test)\\.(ts|tsx|js|jsx)\$' \
                                        || true
                                    """,
                                    returnStdout: true
                                ).trim()

                                /*
                                 * Fail if developer has not added/modified
                                 * test cases.
                                 */
                                if (!changedTestFiles) {

                                    error(
                                        "No unit test files were added or " +
                                        "modified in this PR. " +
                                        "Developer must add/update unit tests " +
                                        "for the new code."
                                    )
                                }

                                echo "Test files changed in this PR:"
                                echo "${changedTestFiles}"

                                /*
                                 * Run only test files changed in this PR.
                                 */
                                def testStatus = sh(
                                    script: """
                                        docker run --rm \
                                          -v "\$(pwd):/usr/src" \
                                          -v /opt/jest-cache:/tmp/jest-cache \
                                          node:22 \
                                          sh -c '
                                              cd /usr/src &&

                                              yarn install --prefer-offline &&

                                              ./node_modules/.bin/jest \
                                                --runTestsByPath ${changedTestFiles} \
                                                --ci \
                                                --coverage \
                                                --coverageReporters=lcov \
                                                --detectOpenHandles \
                                                --forceExit
                                          '
                                    """,
                                    returnStatus: true
                                )

                                echo(
                                    "Changed test files status: ${testStatus}"
                                )

                                if (testStatus != 0) {

                                    error(
                                        "Unit tests added/modified in this PR " +
                                        "failed. Developer needs to fix the " +
                                        "affected tests/code."
                                    )
                                }

                                echo(
                                    "PR unit tests passed successfully"
                                )

                                echo(
                                    "Running Node.js SonarQube analysis"
                                )

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
                                      -Dsonar.test.inclusions="**/*.spec.ts,**/*.test.ts,**/*.spec.tsx,**/*.test.tsx,**/*.spec.js,**/*.test.js,**/*.spec.jsx,**/*.test.jsx" \
                                      -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info \
                                      -Dsonar.exclusions="**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*.interface.ts,**/*.enum.ts,**/*.routing.ts,**/*.routes.ts,**/*.spec.ts,**/*.test.ts,**/*.spec.tsx,**/*.test.tsx,**/*.spec.js,**/*.test.js,**/*.spec.jsx,**/*.test.jsx,**/*.mock.ts,**/*.stub.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,**/assets/**,**/mdo-assets/**,**/themes/**,**/styles/**,**/coverage/**,**/dist/**,**/.angular/**,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js"
                                """

                                echo(
                                    "Node.js SonarQube analysis " +
                                    "completed successfully"
                                )

                            /*
                             * =================================================
                             * PYTHON
                             * =================================================
                             */
                            } else if (
                                env.PROJECT_TYPE == "python"
                            ) {

                                echo(
                                    "Running Python tests changed in this PR"
                                )

                                /*
                                 * Fetch target branch.
                                 */
                                sh """
                                    git fetch origin \
                                      ${env.CHANGE_TARGET}:${env.CHANGE_TARGET} \
                                      || true

                                    echo "PR Target Branch: ${env.CHANGE_TARGET}"

                                    echo "Changed files in this PR:"

                                    git diff \
                                      --name-only \
                                      ${env.CHANGE_TARGET}...HEAD
                                """

                                /*
                                 * Detect Python test files added or modified.
                                 *
                                 * Supported:
                                 *   test_example.py
                                 *   example_test.py
                                 */
                                def changedPythonTestFiles = sh(
                                    script: """
                                        git diff \
                                          --name-only \
                                          --diff-filter=AM \
                                          ${env.CHANGE_TARGET}...HEAD \
                                        | grep -E '(^|/)(test_[^/]+|[^/]+_test)\\.py\$' \
                                        || true
                                    """,
                                    returnStdout: true
                                ).trim()

                                /*
                                 * Fail when PR contains no changed Python test.
                                 */
                                if (!changedPythonTestFiles) {

                                    error(
                                        "No Python unit test files were added " +
                                        "or modified in this PR. Developer " +
                                        "must add/update unit tests for the " +
                                        "new code."
                                    )
                                }

                                echo "Python test files changed in this PR:"
                                echo "${changedPythonTestFiles}"

                                /*
                                 * Convert newline-separated filenames
                                 * to individually shell-quoted arguments.
                                 */
                                def pythonTestFiles =
                                    changedPythonTestFiles
                                        .split('\n')
                                        .collect {
                                            it.trim()
                                        }
                                        .findAll {
                                            it
                                        }
                                        .collect {
                                            "'" +
                                            it.replace(
                                                "'",
                                                "'\"'\"'"
                                            ) +
                                            "'"
                                        }
                                        .join(' ')

                                /*
                                 * Create isolated virtual environment.
                                 *
                                 * This prevents installing Python packages
                                 * globally on the Jenkins host.
                                 */
                                def pythonTestStatus = sh(
                                    script: """
                                        rm -rf .jenkins-pr-venv

                                        python3 -m venv .jenkins-pr-venv

                                        . .jenkins-pr-venv/bin/activate

                                        python --version

                                        python -m pip install --upgrade pip

                                        # Install application dependencies
                                        if [ -f requirements.txt ]; then
                                            python -m pip install \
                                              -r requirements.txt
                                        fi

                                        # Install project-specific test dependencies
                                        if [ -f requirements-test.txt ]; then
                                            python -m pip install \
                                              -r requirements-test.txt
                                        elif [ -f requirements-dev.txt ]; then
                                            python -m pip install \
                                              -r requirements-dev.txt
                                        fi

                                        # Guarantee Jenkins-required test tools
                                        python -m pip install pytest coverage

                                        coverage erase

                                        coverage run \
                                          --source=. \
                                          -m pytest \
                                          ${pythonTestFiles}

                                        TEST_STATUS=\$?

                                        if [ \$TEST_STATUS -eq 0 ]; then
                                            coverage xml -o coverage.xml
                                        fi

                                        exit \$TEST_STATUS
                                    """,
                                    returnStatus: true
                                )

                                echo(
                                    "Changed Python test files status: " +
                                    "${pythonTestStatus}"
                                )

                                if (pythonTestStatus != 0) {

                                    error(
                                        "Python unit tests added/modified " +
                                        "in this PR failed. Developer needs " +
                                        "to fix the affected tests/code."
                                    )
                                }

                                echo(
                                    "PR Python unit tests passed successfully"
                                )

                                /*
                                 * Validate coverage before starting Sonar.
                                 */
                                sh """
                                    test -f coverage.xml

                                    echo "Python coverage report generated:"

                                    ls -lh coverage.xml
                                """

                                echo(
                                    "Running Python SonarQube analysis"
                                )

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
                                      -Dsonar.sources=. \
                                      -Dsonar.tests=. \
                                      -Dsonar.test.inclusions="**/test_*.py,**/*_test.py" \
                                      -Dsonar.python.coverage.reportPaths=coverage.xml \
                                      -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                      -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                      -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                      -Dsonar.exclusions="**/.jenkins-pr-venv/**,**/.venv/**,**/venv/**,**/__pycache__/**,**/*.pyc"
                                """

                                echo(
                                    "Python SonarQube analysis " +
                                    "completed successfully"
                                )

                            /*
                             * =================================================
                             * GENERIC / UNKNOWN
                             * =================================================
                             */
                            } else {

                                echo(
                                    "Running generic SonarQube scan"
                                )

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

                            echo(
                                "SonarQube analysis completed successfully"
                            )
                        }
                    }
                }
            }

            /*
             * ---------------------------------------------------------
             * SonarQube Quality Gate
             * ---------------------------------------------------------
             */
            stage('Quality Gate') {

                when {
                    expression {
                        env.IS_PR_BUILD == "true"
                    }
                }

                steps {

                    timeout(
                        time: 10,
                        unit: 'MINUTES'
                    ) {

                        script {

                            echo(
                                "Waiting for SonarQube Quality Gate..."
                            )

                            def qg = waitForQualityGate(
                                abortPipeline: false
                            )

                            echo(
                                "Quality Gate Status: ${qg.status}"
                            )

                            def repoName = env.GIT_URL
                                .tokenize('/')
                                .last()
                                .replace('.git', '')

                            /*
                             * SonarQube Details link.
                             *
                             * Jenkins status uses BUILD_URL.
                             * Sonar status uses this URL.
                             */
                            def sonarDashboardUrl =
                                "https://dev.karmayogibharat.net/codeanalysis/dashboard" +
                                "?id=${repoName}" +
                                "&pullRequest=${env.CHANGE_ID}"

                            def githubState =
                                qg.status == 'OK' ?
                                    'success' :
                                    'failure'

                            def githubDescription =
                                qg.status == 'OK' ?
                                    'SonarQube Quality Gate Passed' :
                                    "SonarQube Quality Gate Failed: " +
                                    "${qg.status}"

                            /*
                             * Send independent Sonar GitHub status.
                             */
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
                                    "SonarQube Quality Gate Failed: " +
                                    "${qg.status}"
                                )
                            }

                            echo(
                                "SonarQube Quality Gate Passed"
                            )
                        }
                    }
                }
            }
        }

        /*
         * -------------------------------------------------------------
         * Final Pipeline Status
         * -------------------------------------------------------------
         */
        post {

            success {

                script {

                    if (env.CHANGE_ID) {

                        updateJenkinsGitHubStatus(
                            'success',
                            'Jenkins PR Validation Passed'
                        )
                    }

                    echo "PR Validation Successful"

                    echo(
                        "Manager can now review and merge the PR."
                    )
                }
            }

            failure {

                script {

                    if (env.CHANGE_ID) {

                        updateJenkinsGitHubStatus(
                            'failure',
                            'Jenkins PR Validation Failed'
                        )
                    }

                    echo "PR Validation Failed"

                    echo(
                        "Merge will be blocked by " +
                        "GitHub Branch Protection."
                    )
                }
            }

            always {

                echo "PR Validation Pipeline Completed"
            }
        }
    }
}
