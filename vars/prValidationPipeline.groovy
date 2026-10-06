def call(Map config = [:]) {

    /*
     * ---------------------------------------------------------
     * Constants
     * ---------------------------------------------------------
     *
     * Test-file patterns (Groovy regex, matched against the full path)
     */
    def JAVA_TEST_PATTERN   = '(?:.*/)?src/test/java/.*(?:Test|Tests|TestCase)\\.java'
    def NODE_TEST_PATTERN   = '.*\\.(?:spec|test)\\.(?:ts|tsx|js|jsx)'
    def PYTHON_TEST_PATTERN = '(?:.*/)?(?:test_[^/]+|[^/]+_test)\\.py'

    /*
     * Shared cache directories on the Jenkins agent.
     * They survive between builds and are what makes reruns fast.
     */
    def SONAR_CACHE = '/opt/sonar-cache'
    def JEST_CACHE  = '/opt/jest-cache'
    def YARN_CACHE  = '/opt/yarn-cache'
    def PIP_CACHE   = '/opt/pip-cache'

    /*
     * Data collected during the run and rendered into the PR comment.
     */
    def report = [
        jira       : '',
        projectType: 'unknown',
        testFiles  : [],
        testResult : 'not run',
        sonarResult: 'not run',
        sonarUrl   : ''
    ]

    /*
     * ---------------------------------------------------------
     * Helpers
     * ---------------------------------------------------------
     */

    // KB-iGOT/<repo> name from GIT_URL
    def getRepoName = {
        env.GIT_URL
            .tokenize('/')
            .last()
            .replace('.git', '')
    }

    // Safe single-quoting for shell arguments
    def shellQuote = { String value ->
        "'" + value.replace("'", "'\"'\"'") + "'"
    }

    /*
     * Generic GitHub commit status.
     * Descriptions are sanitized (GitHub limit is 140 chars, and quotes /
     * newlines would break the JSON payload).
     */
    def postGitHubStatus = { String context, String state, String description, String targetUrl ->

        def safeDescription = (description ?: '')
            .replaceAll(/["'\\\r\n]+/, ' ')
            .take(140)

        def repoName = getRepoName()

        echo "Updating GitHub status [${context}]: ${state}"
        echo "Status message: ${safeDescription}"

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
                    "target_url": "${targetUrl}",
                    "description": "${safeDescription}",
                    "context": "${context}"
                  }'
            """
        }
    }

    // Jenkins context: continuous-integration/jenkins/pr-head -> BUILD_URL
    def updateJenkinsGitHubStatus = { String state, String description ->
        postGitHubStatus(
            'continuous-integration/jenkins/pr-head',
            state,
            description,
            env.BUILD_URL
        )
    }

    /*
     * True when this build was cancelled because a newer push to the same PR
     * started another build (disableConcurrentBuilds abortPrevious).
     * Such a build must stay silent: no status, no PR comment. Otherwise its
     * "Superseded by #N" result overwrites the newer build's comment.
     */
    def isSuperseded = {
        return currentBuild.currentResult == 'NOT_BUILT' ||
               (env.PR_FAILURE_REASON ?: '').contains('Superseded by')
    }

    /*
     * Final (non-success) Jenkins status that survives the plugin's own
     * notification.
     *
     * The GitHub Branch Source plugin posts its generic text
     * ("This commit cannot be built") on the same pr-head context AFTER the
     * pipeline finishes, replacing our specific reason. So: post now, and
     * re-post once from a detached background process a few seconds later.
     * The Jenkins node is persistent, so the process outlives the build.
     * Best effort only: any problem here is logged and never fails the build.
     */
    def reportFinalJenkinsStatus = { String state, String description ->

        updateJenkinsGitHubStatus(state, description)

        try {

            def safeDescription = (description ?: '')
                .replaceAll(/["'\\\r\n]+/, ' ')
                .take(140)

            def repoName    = getRepoName()
            def payloadFile = "/tmp/pr-head-final-" +
                env.BUILD_TAG.replaceAll('[^A-Za-z0-9._-]', '_') + ".json"

            writeFile(
                file: payloadFile,
                text: groovy.json.JsonOutput.toJson([
                    state      : state,
                    target_url : env.BUILD_URL,
                    description: safeDescription,
                    context    : 'continuous-integration/jenkins/pr-head'
                ])
            )

            withCredentials([
                usernamePassword(
                    credentialsId: 'github-cred',
                    usernameVariable: 'GITHUB_USER',
                    passwordVariable: 'GITHUB_TOKEN'
                )
            ]) {
                sh """
                    export JENKINS_NODE_COOKIE=dontKillMe
                    export BUILD_ID=dontKillMe

                    nohup setsid bash -c '
                        sleep 25
                        curl -sS --fail-with-body \
                          --request POST \
                          --header "Accept: application/vnd.github+json" \
                          --header "Authorization: Bearer \$GITHUB_TOKEN" \
                          --header "X-GitHub-Api-Version: 2022-11-28" \
                          --data @"${payloadFile}" \
                          "https://api.github.com/repos/KB-iGOT/${repoName}/statuses/${env.GIT_COMMIT}" \
                          >/dev/null 2>&1
                        rm -f "${payloadFile}"
                    ' >/dev/null 2>&1 &
                """
            }

        } catch (err) {

            echo "WARNING: could not schedule final status re-post: ${err.getMessage()}"
        }
    }

    /*
     * Fail the PR with a specific reason:
     * remembers it (post blocks re-use it), posts it, fails the build.
     */
    def failPR = { String githubMessage, String errorMessage ->
        env.PR_FAILURE_REASON = githubMessage
        updateJenkinsGitHubStatus('failure', githubMessage)
        error(errorMessage)
    }

    /*
     * Close out sonarqube/quality-gate so the PR never shows it as
     * "expected - waiting". No-op if Quality Gate already posted the result.
     */
    def closeOutSonarStatus = { String state, String description ->
        if (env.CHANGE_ID && env.SONAR_STATUS_POSTED != 'true') {
            postGitHubStatus(
                'sonarqube/quality-gate',
                state,
                description,
                env.BUILD_URL
            )
        }
    }

    /*
     * Fetch the PR target branch and log the changed files.
     * Fails clearly if the fetch fails (no silent diff against a stale ref).
     */
    def fetchTargetAndListChanges = {
        def fetchStatus = sh(
            script: """
                git fetch origin +refs/heads/${env.CHANGE_TARGET}:refs/remotes/origin/${env.CHANGE_TARGET}
            """,
            returnStatus: true
        )

        if (fetchStatus != 0) {
            failPR(
                "Could not fetch target branch ${env.CHANGE_TARGET} for PR diff. Check Jenkins logs",
                "git fetch of origin/${env.CHANGE_TARGET} failed with exit code ${fetchStatus}"
            )
        }

        sh """
            echo "PR Target Branch: ${env.CHANGE_TARGET}"

            echo "Changed files in this PR:"

            git diff --name-only origin/${env.CHANGE_TARGET}...HEAD
        """
    }

    /*
     * Return the test files added/modified in this PR (matching `pattern`).
     * Fails the PR if there are none.
     */
    def requireChangedTests = { String label, String pattern ->

        fetchTargetAndListChanges()

        def changed = sh(
            script: "git diff --name-only --diff-filter=AM origin/${env.CHANGE_TARGET}...HEAD",
            returnStdout: true
        ).trim()

        def testFiles = []

        if (changed) {
            for (f in changed.split('\n')) {
                def path = f.trim()
                if (path && (path ==~ pattern)) {
                    testFiles << path
                }
            }
        }

        report.testFiles = testFiles

        if (!testFiles) {
            report.testResult = 'no unit tests added or modified'
            failPR(
                "No ${label} unit tests added or modified in this PR",
                "No ${label} unit test files were added or modified in this PR. " +
                "Developer must add/update unit tests for the new code."
            )
        }

        echo "${label} test files changed in this PR:"
        echo "${testFiles.join('\n')}"

        return testFiles
    }

    /*
     * Evaluate a test run's exit status.
     */
    def checkTestResult = { String label, int status ->

        echo "Changed ${label} test files status: ${status}"

        if (status != 0) {
            report.testResult = 'failed'
            failPR(
                "${label} unit tests failed. Check Jenkins logs",
                "${label} unit tests added/modified in this PR failed. " +
                "Developer needs to fix the affected tests/code."
            )
        }

        report.testResult = 'passed'
        echo "PR ${label} unit tests passed successfully"
    }

    /*
     * Run sonar-scanner with the arguments common to Node / Python / generic
     * projects, plus per-language `extraArgs`.
     * Must be called inside withSonarQubeEnv (uses SONAR_HOST_URL / SONAR_AUTH_TOKEN).
     */
    def runSonarScanner = { List extraArgs ->

        def scannerHome = tool 'sonar-scanner'
        def repoName    = getRepoName()
        def extra       = extraArgs.join(" \\\n  ")

        sh """
            rm -rf .scannerwork || true
            mkdir -p .scannerwork ${SONAR_CACHE} || true

            export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
            export PATH=\$JAVA_HOME/bin:\$PATH

            java -version

            ${scannerHome}/bin/sonar-scanner \
              -Dsonar.scanner.skipJreProvisioning=true \
              -Dsonar.host.url="\$SONAR_HOST_URL" \
              -Dsonar.token="\$SONAR_AUTH_TOKEN" \
              -Dsonar.projectKey="${repoName}" \
              -Dsonar.userHome=${SONAR_CACHE} \
              -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
              -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
              -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
              ${extra}
        """
    }

    /*
     * Create/update a single "sticky" summary comment on the PR.
     * Never fails the build: any error is logged as a warning.
     */
    def postPRComment = { String overall ->

        if (!env.CHANGE_ID) {
            return
        }

        try {

            def marker   = '<!-- jenkins-pr-validation -->'
            def repoName = getRepoName()

            def overallText = [
                SUCCESS : '✅ PASSED',
                FAILURE : '❌ FAILED',
                UNSTABLE: '⚠️ UNSTABLE',
                ABORTED : '⏹ ABORTED'
            ][overall] ?: overall

            def jiraText = report.jira ?
                "`${report.jira}`" :
                '⚠️ Not found in PR title (not blocking)'

            def lines = []
            lines << marker
            lines << "### Jenkins PR validation: ${overallText}"
            lines << ''
            lines << '| Check | Result |'
            lines << '|---|---|'
            lines << "| Jira ID | ${jiraText} |"
            lines << "| Project type | ${report.projectType} |"
            lines << "| Unit test files changed | ${report.testFiles.size()} |"
            lines << "| Unit tests | ${report.testResult} |"
            lines << "| SonarQube Quality Gate | ${report.sonarResult} |"
            lines << ''

            if (env.PR_FAILURE_REASON) {
                lines << "**Reason:** ${env.PR_FAILURE_REASON}"
                lines << ''
            }

            if (report.testFiles) {
                lines << '<details><summary>Unit test files in this PR</summary>'
                lines << ''
                for (f in report.testFiles) {
                    lines << "- `${f}`"
                }
                lines << ''
                lines << '</details>'
                lines << ''
            }

            def links = "[Jenkins build](${env.BUILD_URL}) | [Console log](${env.BUILD_URL}console)"
            if (report.sonarUrl) {
                links += " | [SonarQube analysis](${report.sonarUrl})"
            }
            lines << links
            lines << ''
            lines << "_Commit ${(env.GIT_COMMIT ?: '').take(7)}_"

            def payload = groovy.json.JsonOutput.toJson([body: lines.join('\n')])
            writeFile file: '.pr-comment.json', text: payload

            withCredentials([
                usernamePassword(
                    credentialsId: 'github-cred',
                    usernameVariable: 'GITHUB_USER',
                    passwordVariable: 'GITHUB_TOKEN'
                )
            ]) {

                def existing = sh(
                    script: """
                        curl -sS --fail-with-body \
                          --header "Accept: application/vnd.github+json" \
                          --header "Authorization: Bearer \$GITHUB_TOKEN" \
                          --header "X-GitHub-Api-Version: 2022-11-28" \
                          "https://api.github.com/repos/KB-iGOT/${repoName}/issues/${env.CHANGE_ID}/comments?per_page=100"
                    """,
                    returnStdout: true
                ).trim()

                def commentId = null

                if (existing) {
                    def comments = new groovy.json.JsonSlurperClassic().parseText(existing)
                    for (c in comments) {
                        if (c.body?.contains(marker)) {
                            commentId = c.id
                            break
                        }
                    }
                }

                def method = commentId ? 'PATCH' : 'POST'
                def url    = commentId ?
                    "https://api.github.com/repos/KB-iGOT/${repoName}/issues/comments/${commentId}" :
                    "https://api.github.com/repos/KB-iGOT/${repoName}/issues/${env.CHANGE_ID}/comments"

                sh """
                    curl -sS --fail-with-body --output /dev/null \
                      --request ${method} \
                      --header "Accept: application/vnd.github+json" \
                      --header "Authorization: Bearer \$GITHUB_TOKEN" \
                      --header "X-GitHub-Api-Version: 2022-11-28" \
                      --header "Content-Type: application/json" \
                      --data @.pr-comment.json \
                      "${url}"
                """
            }

        } catch (err) {

            echo "WARNING: could not post PR comment: ${err.getMessage()}"
        }
    }

    /*
     * ---------------------------------------------------------
     * Pipeline
     * ---------------------------------------------------------
     */
    pipeline {

        agent any

        options {
            // Hung docker/mvn/scanner runs must not hold the agent forever
            timeout(time: 45, unit: 'MINUTES')
            timestamps()
            // A new push to the PR cancels the older build for that PR
            disableConcurrentBuilds(abortPrevious: true)
        }

        environment {
            SONARQUBE_ENV = "sonarqube"
        }

        stages {

            /*
             * ---------------------------------------------------------
             * Initialize PR Validation
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
                            'Jenkins PR validation is running'
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

                        report.projectType = env.PROJECT_TYPE

                        echo "Detected Project Type: ${env.PROJECT_TYPE}"
                    }
                }
            }

            /*
             * ---------------------------------------------------------
             * Extract Jira Ticket  (informational only - never blocks)
             * ---------------------------------------------------------
             */
            stage('Extract Jira Ticket') {

                steps {
                    script {

                        def commitMsg = env.CHANGE_TITLE ?: ""

                        echo "PR Title: ${commitMsg}"

                        def jiraMatch = commitMsg.find(/KB-\d+/)

                        if (jiraMatch) {

                            env.JIRA_ID = jiraMatch
                            report.jira = jiraMatch

                            echo "Jira Ticket Found: ${env.JIRA_ID}"

                        } else {

                            env.JIRA_ID = ""
                            report.jira = ""

                            echo "No Jira ID found in PR title."
                            echo "Continuing PR validation."

                            currentBuild.description =
                                "Warning: No Jira ID found in PR title"
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

                        if (!env.CHANGE_ID) {

                            echo(
                                "Not a Pull Request build. " +
                                "Skipping Sonar PR validation."
                            )

                            return
                        }

                        def repoName = getRepoName()

                        echo "Running SonarQube PR analysis"
                        echo "Repository: ${repoName}"
                        echo "PR Number: ${env.CHANGE_ID}"
                        echo "Source Branch: ${env.CHANGE_BRANCH}"
                        echo "Target Branch: ${env.CHANGE_TARGET}"

                        /*
                         * Catch-all: unexpected failures (docker, mvn, scanner,
                         * network) still get a meaningful GitHub message.
                         * A reason already set by failPR is never overwritten.
                         */
                        try {

                            withSonarQubeEnv("${SONARQUBE_ENV}") {

                                /*
                                 * =============================================
                                 * JAVA
                                 * =============================================
                                 */
                                if (env.PROJECT_TYPE == "java") {

                                    def testFiles = requireChangedTests('Java', JAVA_TEST_PATTERN)

                                    /*
                                     * src/test/java/com/example/UserServiceTest.java
                                     *   -> com.example.UserServiceTest
                                     */
                                    def javaTestClasses = testFiles
                                        .collect {
                                            it.replaceFirst('^.*?src/test/java/', '')
                                              .replaceFirst('\\.java$', '')
                                              .replace('/', '.')
                                        }
                                        .join(',')

                                    echo "Java tests selected for execution: ${javaTestClasses}"

                                    /*
                                     * Single Maven run: compile + only the PR's
                                     * tests + jacoco report. Sonar then reuses
                                     * target/ instead of re-running the tests.
                                     */
                                    def mavenStatus = sh(
                                        script: """
                                            export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                            export PATH="\$JAVA_HOME/bin:\$PATH"

                                            java -version

                                            /var/lib/jenkins/apache-maven-3.8.8/bin/mvn -B -ntp \
                                              clean verify \
                                              -Dtest="${javaTestClasses}" \
                                              -Djacoco.haltOnFailure=false
                                        """,
                                        returnStatus: true
                                    )

                                    checkTestResult('Java', mavenStatus)

                                    echo "Running Java/Maven SonarQube analysis"

                                    sh """
                                        export JAVA_HOME=/var/lib/jenkins/jdk-17.0.12
                                        export PATH="\$JAVA_HOME/bin:\$PATH"

                                        mkdir -p ${SONAR_CACHE} || true

                                        /var/lib/jenkins/apache-maven-3.8.8/bin/mvn -B -ntp \
                                          sonar:sonar \
                                          -Dsonar.host.url="\$SONAR_HOST_URL" \
                                          -Dsonar.token="\$SONAR_AUTH_TOKEN" \
                                          -Dsonar.projectKey="${repoName}" \
                                          -Dsonar.userHome=${SONAR_CACHE} \
                                          -Dsonar.pullrequest.key="${env.CHANGE_ID}" \
                                          -Dsonar.pullrequest.branch="${env.CHANGE_BRANCH}" \
                                          -Dsonar.pullrequest.base="${env.CHANGE_TARGET}" \
                                          -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                                    """

                                /*
                                 * =============================================
                                 * NODE.JS
                                 * =============================================
                                 */
                                } else if (env.PROJECT_TYPE == "node") {

                                    def testFiles = requireChangedTests('Node.js', NODE_TEST_PATTERN)

                                    // One path per line; read by xargs inside the container
                                    // (safe for spaces/quotes in file names)
                                    writeFile(
                                        file: '.pr-test-files.txt',
                                        text: testFiles.join('\n') + '\n'
                                    )

                                    /*
                                     * yarn + jest caches are mounted from the agent,
                                     * so reruns skip re-downloading packages.
                                     */
                                    def testStatus = sh(
                                        script: """
                                            mkdir -p ${JEST_CACHE} ${YARN_CACHE} 2>/dev/null || true

                                            # Run as the Jenkins user (not root) so node_modules,
                                            # coverage etc. in the workspace stay deletable by cleanWs
                                            docker run --rm \
                                              -u "\$(id -u):\$(id -g)" \
                                              -e HOME=/tmp \
                                              -v "\$(pwd):/usr/src" \
                                              -v ${JEST_CACHE}:/tmp/jest-cache \
                                              -v ${YARN_CACHE}:/tmp/yarn-cache \
                                              -e YARN_CACHE_FOLDER=/tmp/yarn-cache \
                                              node:22 \
                                              sh -c '
                                                  cd /usr/src &&

                                                  yarn install --prefer-offline &&

                                                  xargs -d "\\n" ./node_modules/.bin/jest \
                                                    --ci \
                                                    --coverage \
                                                    --coverageReporters=lcov \
                                                    --cacheDirectory=/tmp/jest-cache \
                                                    --detectOpenHandles \
                                                    --forceExit \
                                                    --runTestsByPath < .pr-test-files.txt
                                              '
                                        """,
                                        returnStatus: true
                                    )

                                    checkTestResult('Node.js', testStatus)

                                    echo "Running Node.js SonarQube analysis"

                                    runSonarScanner([
                                        '-Dsonar.sources=src',
                                        '-Dsonar.tests=.',
                                        '-Dsonar.verbose=true',
                                        '-Dsonar.test.inclusions="**/*.spec.ts,**/*.test.ts,**/*.spec.tsx,**/*.test.tsx,**/*.spec.js,**/*.test.js,**/*.spec.jsx,**/*.test.jsx"',
                                        '-Dsonar.typescript.lcov.reportPaths=coverage/lcov.info',
                                        '-Dsonar.exclusions="**/node_modules/**,**/*.module.ts,**/*.model.ts,**/*.interface.ts,**/*.enum.ts,**/*.routing.ts,**/*.routes.ts,**/*.spec.ts,**/*.test.ts,**/*.spec.tsx,**/*.test.tsx,**/*.spec.js,**/*.test.js,**/*.spec.jsx,**/*.test.jsx,**/*.mock.ts,**/*.stub.ts,**/*setup-jest.ts,**/*main.ts,**/*environment.*.ts,**/*test.ts,**/assets/**,**/mdo-assets/**,**/themes/**,**/styles/**,**/coverage/**,**/dist/**,**/.angular/**,protractor.conf.js,babel.config.js,jest.config.js,jest.env.js,test/mocks/*.*,karma.conf.js"'
                                    ])

                                /*
                                 * =============================================
                                 * PYTHON
                                 * =============================================
                                 */
                                } else if (env.PROJECT_TYPE == "python") {

                                    def testFiles = requireChangedTests('Python', PYTHON_TEST_PATTERN)

                                    def pythonTestFiles = testFiles
                                        .collect { shellQuote(it) }
                                        .join(' ')

                                    /*
                                     * Fresh venv each run (clean, predictable);
                                     * pip's download cache is shared, so installs
                                     * are fast after the first run.
                                     */
                                    def pythonTestStatus = sh(
                                        script: """
                                            export PIP_CACHE_DIR=${PIP_CACHE}
                                            mkdir -p "\$PIP_CACHE_DIR" 2>/dev/null || true

                                            rm -rf .jenkins-pr-venv

                                            python3 -m venv .jenkins-pr-venv

                                            . .jenkins-pr-venv/bin/activate

                                            python --version

                                            python -m pip install --upgrade pip

                                            # Application dependencies
                                            if [ -f requirements.txt ]; then
                                                python -m pip install \
                                                  -r requirements.txt
                                            fi

                                            # Project test dependencies
                                            if [ -f requirements-test.txt ]; then
                                                python -m pip install \
                                                  -r requirements-test.txt
                                            elif [ -f requirements-dev.txt ]; then
                                                python -m pip install \
                                                  -r requirements-dev.txt
                                            fi

                                            # Jenkins-required testing tools
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

                                    checkTestResult('Python', pythonTestStatus)

                                    // Coverage must exist before Sonar
                                    sh """
                                        test -f coverage.xml

                                        echo "Python coverage report generated:"

                                        ls -lh coverage.xml
                                    """

                                    echo "Running Python SonarQube analysis"

                                    runSonarScanner([
                                        '-Dsonar.sources=.',
                                        '-Dsonar.tests=.',
                                        '-Dsonar.test.inclusions="**/test_*.py,**/*_test.py"',
                                        '-Dsonar.python.coverage.reportPaths=coverage.xml',
                                        '-Dsonar.exclusions="**/.jenkins-pr-venv/**,**/.venv/**,**/venv/**,**/__pycache__/**,**/*.pyc"'
                                    ])

                                /*
                                 * =============================================
                                 * GENERIC (no unit-test requirement)
                                 * =============================================
                                 */
                                } else {

                                    report.testResult = 'not required for this project type'

                                    echo "Running generic SonarQube scan"

                                    runSonarScanner([
                                        '-Dsonar.sources=.'
                                    ])

                                    sh """
                                        echo "Checking report-task.txt"
                                        ls -ltr report-task.txt || true
                                        cat report-task.txt || true
                                    """
                                }

                                echo "SonarQube analysis completed successfully"
                            }

                        } catch (err) {

                            if (!env.PR_FAILURE_REASON) {
                                env.PR_FAILURE_REASON =
                                    "Analysis stage failed: ${err.getMessage()}"
                            }

                            throw err
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

                    script {

                        try {

                            timeout(
                                time: 10,
                                unit: 'MINUTES'
                            ) {

                                echo "Waiting for SonarQube Quality Gate..."

                                def qg = waitForQualityGate(
                                    abortPipeline: false
                                )

                                echo "Quality Gate Status: ${qg.status}"

                                def repoName = getRepoName()

                                /*
                                 * Sonar remains separate from Jenkins.
                                 *
                                 * Jenkins Details -> Jenkins
                                 * Sonar Details   -> SonarQube
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
                                        "SonarQube Quality Gate Failed: ${qg.status}"

                                postGitHubStatus(
                                    'sonarqube/quality-gate',
                                    githubState,
                                    githubDescription,
                                    sonarDashboardUrl
                                )

                                // Real Sonar result is on GitHub; post blocks must not overwrite it
                                env.SONAR_STATUS_POSTED = 'true'

                                report.sonarUrl = sonarDashboardUrl
                                report.sonarResult =
                                    qg.status == 'OK' ?
                                        'Passed' :
                                        "Failed (${qg.status})"

                                if (qg.status != 'OK') {

                                    // Jenkins status also says why (details are on the SonarQube check)
                                    env.PR_FAILURE_REASON =
                                        "SonarQube Quality Gate failed: ${qg.status}. See SonarQube check"

                                    error(
                                        "SonarQube Quality Gate Failed: " +
                                        "${qg.status}"
                                    )
                                }

                                echo "SonarQube Quality Gate Passed"
                            }

                        } catch (err) {

                            // Covers timeouts / Sonar connectivity errors as well
                            if (!env.PR_FAILURE_REASON) {
                                env.PR_FAILURE_REASON =
                                    "Quality Gate stage failed: ${err.getMessage()}"
                            }

                            throw err
                        }
                    }
                }
            }
        }

        /*
         * -------------------------------------------------------------
         * Final statuses
         * -------------------------------------------------------------
         * Declarative runs `always` first, then aborted / failure /
         * success / unstable.
         */
        post {

            always {

                script {

                    if (isSuperseded()) {

                        echo "Build superseded by a newer one; skipping PR comment"

                    } else {

                        // Sticky summary comment on the PR (never fails the build)
                        postPRComment(currentBuild.currentResult)
                    }

                    echo "PR Validation Pipeline Completed"
                }
            }

            success {

                script {

                    if (env.CHANGE_ID) {

                        updateJenkinsGitHubStatus(
                            'success',
                            'Jenkins PR validation passed'
                        )
                    }

                    echo "PR Validation Successful"
                    echo "Manager can now review and merge the PR."
                }
            }

            failure {

                script {

                    /*
                     * Re-post the specific reason captured earlier so the
                     * generic message never overwrites it on GitHub.
                     */
                    if (isSuperseded()) {

                        echo "Build superseded by a newer one; skipping GitHub statuses"
                        return
                    }

                    if (env.CHANGE_ID) {

                        reportFinalJenkinsStatus(
                            'failure',
                            env.PR_FAILURE_REASON ?:
                                'Jenkins PR validation failed. Check build logs'
                        )
                    }

                    // Failed before Quality Gate posted its result: close that context too
                    closeOutSonarStatus(
                        'failure',
                        "Not run: ${env.PR_FAILURE_REASON ?: 'Jenkins validation failed before Quality Gate'}"
                    )

                    echo "PR Validation Failed: ${env.PR_FAILURE_REASON ?: 'see logs'}"

                    echo(
                        "Merge will be blocked by " +
                        "GitHub Branch Protection."
                    )
                }
            }

            /*
             * Aborted (manual abort, newer push, Jenkins restart, or the
             * 45 min timeout). Without this, "pending" would stay forever.
             */
            aborted {

                script {

                    if (isSuperseded()) {

                        echo "Build superseded by a newer one; skipping GitHub statuses"
                        return
                    }

                    if (env.CHANGE_ID) {

                        reportFinalJenkinsStatus(
                            'error',
                            env.PR_FAILURE_REASON ?:
                                'Jenkins PR validation was aborted or timed out'
                        )

                        closeOutSonarStatus(
                            'error',
                            'Not run: Jenkins PR validation was aborted or timed out'
                        )
                    }

                    echo "PR Validation Aborted"
                }
            }

            /*
             * Runs last, after every other post block (and after the PR
             * comment, which reads files from the workspace).
             * Deletes this build's workspace (node_modules, coverage,
             * target, venv, clone: ~1.7 GB for sunbird-cb-portal). The shared
             * caches under /opt (yarn, jest, sonar, pip) and ~/.m2 are outside
             * the workspace, so reruns stay fast.
             * Never fails the build.
             */
            cleanup {

                cleanWs(
                    deleteDirs: true,
                    disableDeferredWipeout: true,
                    notFailBuild: true
                )
            }

            unstable {

                script {

                    if (env.CHANGE_ID) {

                        reportFinalJenkinsStatus(
                            'failure',
                            env.PR_FAILURE_REASON ?:
                                'Jenkins PR validation finished unstable. Check build logs'
                        )

                        closeOutSonarStatus(
                            'failure',
                            'Not run: Jenkins PR validation finished unstable'
                        )
                    }

                    echo "PR Validation Unstable"
                }
            }
        }
    }
}
