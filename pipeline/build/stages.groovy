#!groovy

import groovy.json.*
import groovy.transform.Field

// params are loaded from properties file
load 'infrastructure/pipeline/build/properties.groovy'
utils = load 'infrastructure/pipeline/build/utils.groovy'

@Field String triggeredRepoName
@Field Map gitRepos
@Field Map buildInfo

def initialize() {
  String triggeredRepoURL = scm.userRemoteConfigs.get(0).url
  triggeredRepoName =
    triggeredRepoURL.tokenize('/').last().tokenize('.').first()
  echo "Build triggered from $triggeredRepoName repository"

  utils.setGithubStatus(
    triggeredRepoName, 'Initializing pipeline', 'PENDING')

  gitRepos = utils.getGitRepos(triggeredRepoName)

  buildInfo = [:]
}

def authorize() {
  utils.setGithubStatus(
    triggeredRepoName, 'Checking for author authorization', 'PENDING')

  author = env.CHANGE_AUTHOR
  echo "Change author: $author"

  if (author == null) {
    echo 'Executed from interface or by direct push to branch, ' +
      'assuming user is authorized'
    return
  }

  List authorizedUsers = []
  if (params.GITHUB_AUTHORIZED_USERS.isEmpty()) {
    echo("Retrieving authorized users from GitHub organization " +
         params.GITHUB_ORGANIZATION_NAME)
    String orgMembersURL = ("https://api.github.com/orgs/" +
                            "$params.GITHUB_ORGANIZATION_NAME/public_members")
    response = httpRequest(url: orgMembersURL,
                           authentication: 'github-user-pass-credentials')
    JsonSlurper jsonSlurper = new JsonSlurper()
    List responseList = jsonSlurper.parseText(response.content)
    for (Map user : responseList) {
      authorizedUsers += user.login
    }
  } else {
    authorizedUsers = params.GITHUB_AUTHORIZED_USERS.tokenize()
  }
  echo "Authorized GitHub users: " + authorizedUsers.join(", ")

  if (author in authorizedUsers) {
    echo "Author is authorized"
  } else {
    utils.setGithubStatus(
      triggeredRepoName, 'Waiting for manual authorization', 'PENDING')
    input message: "$author does not have write access to the repository"
  }
}

def validate() {
  utils.setGithubStatus(
    triggeredRepoName, 'Executing file validations', 'PENDING')

  if (triggeredRepoName == 'infrastructure') {
    echo "No validation for $triggeredRepoName repository"
    return
  }

  utils.checkoutRepo('builds', gitRepos)
  if (triggeredRepoName == 'versions') {
    utils.checkoutRepo('versions', gitRepos)
  }

  String dirToValidate = pwd() + "/$triggeredRepoName"
  Map validations = [
    YAMLlint: {
      sh "./scripts/validate_yamls.py -d $dirToValidate"
    }
  ]

  if (triggeredRepoName == 'builds') {
    validations['Pylint'] = {
      String pythonFiles = sh(script: 'find -name "*.py"',
                              returnStdout: true).replaceAll('\n', ' ')
      sh "pylint $pythonFiles"
    }
    validations['Unit tests'] = {
      sh 'nosetests tests/unit'
    }
  } else if (triggeredRepoName == 'versions') {
    validations['RPMlint'] = {
      sh "./scripts/validate_rpm_specs.py -d $dirToValidate"
    }
  }

  dir('builds') {
    parallel(validations)
  }
}

def buildPackages() {
  utils.setGithubStatus(
    triggeredRepoName, 'Building packages', 'PENDING')

  deleteDir()
  dir("$params.BUILDS_WORKSPACE_DIR/mock_build") {
    deleteDir()
  }
  utils.checkoutRepo('builds', gitRepos)

  buildInfo.BUILD_STATUS = 'FAIL'
  buildInfo.BUILD_ID = currentBuild.getNumber()
  buildInfo.BUILD_LOG = currentBuild.getAbsoluteUrl() + 'consoleFull'
  String VERSIONS_REPO_URL =
    gitRepos['versions'].userRemoteConfigs.get(0).url
  String VERSIONS_REPO_ACTUAL_REFERENCE =
    gitRepos['versions'].branches.get(0).name
  String VERSIONS_REPO_DIR = "versions_build-packages"
  String VERSIONS_REPO_PATH =
    "$params.BUILDS_WORKSPACE_DIR/repositories/$VERSIONS_REPO_DIR"

  dir('builds') {
    // Tell mock to use a different mirror/repo.
    // This could be used to:
    // - speedup the chroot installation
    // - use a different version of CentOS
    // - workaround any issue with CentOS official mirrors
    if (params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL) {
      utils.replaceInFile(params.MOCK_CONFIG_FILE,
                    params.MAIN_CENTOS_REPO_RELEASE_URL,
                    params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
    }

    echo 'Building packages'
    String packagesParameter = ''
    if (params.PACKAGES) {
      packagesParameter = "--packages $PACKAGES"
    }
    catchError {
      sh """\
python host_os.py \\
       --verbose \\
       --work-dir $params.BUILDS_WORKSPACE_DIR \\
       build-packages \\
           --force-rebuild \\
           --keep-build-dir \\
           --packages-metadata-repo-url $VERSIONS_REPO_URL \\
           --packages-metadata-repo-branch $VERSIONS_REPO_ACTUAL_REFERENCE \\
           $packagesParameter \\
           $params.BUILD_ISO_EXTRA_PARAMETERS \\
"""
      buildInfo.BUILD_STATUS = 'PASS'
    }

    dir('result/packages') {
      File latestBuildDir = new File(pwd(), 'latest')
      String timestamp = latestBuildDir.getCanonicalPath().tokenize('/').last()
      echo "Build timestamp: $timestamp"
      buildInfo.BUILD_TIMESTAMP = timestamp
    }

    // The commit hashes should be accessible from the GitSCM object
    // https://issues.jenkins-ci.org/browse/JENKINS-34455
    buildInfo.BUILDS_REPO_COMMIT = sh(
      script:"git rev-parse HEAD", returnStdout: true).trim()
  }

  dir(VERSIONS_REPO_PATH) {
    buildInfo.VERSIONS_REPO_COMMIT = sh(
      script:"git rev-parse HEAD", returnStdout: true).trim()
  }

  sh 'ln -s builds/result/packages/latest repository'
  stash name: 'repository_dir', includes: 'repository/'
  archiveArtifacts artifacts: 'repository/'
}

def buildIso() {
  utils.setGithubStatus(
    triggeredRepoName, 'Building ISO', 'PENDING')

  deleteDir()
  utils.checkoutRepo('builds', gitRepos)

  unstash 'repository_dir'

  // Convert timestamp from format YYYY-MM-DDThh:mm:ss.ssssss
  // to YYYYMMDDThhmmss
  String ISO_VERSION = buildInfo.BUILD_TIMESTAMP
  ISO_VERSION = ISO_VERSION.replaceAll(/-/, '')
  ISO_VERSION = ISO_VERSION.replaceAll(/:/, '')
  ISO_VERSION = ISO_VERSION.replaceFirst(/.*/, '')

  dir('builds') {
    // Tell mock and pungi to use different CentOS and EPEL mirrors/repos.
    // This could be used to:
    // - speedup the chroot installation
    // - use a different version of CentOS
    // - workaround any issue with CentOS official mirrors
    if (params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL) {
      utils.replaceInFile(params.MOCK_CONFIG_FILE,
                    params.MAIN_CENTOS_REPO_RELEASE_URL,
                    params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
      utils.replaceInFile(params.BUILDS_CONFIG_FILE,
                    params.MAIN_CENTOS_REPO_RELEASE_URL,
                    params.CENTOS_ALTERNATE_MIRROR_RELEASE_URL)
    }
    if (params.EPEL_ALTERNATE_MIRROR_RELEASE_URL) {
      // The mock configuration file currently has a mirror list URL for
      // EPEL, which can't be replaced in the same manner as the others.
      utils.replaceInFile(params.BUILDS_CONFIG_FILE,
                    params.MAIN_EPEL_REPO_RELEASE_URL,
                    params.EPEL_ALTERNATE_MIRROR_RELEASE_URL)
    }

    echo 'Building ISO'
    catchError {
      sh """\
python host_os.py \\
       --verbose \\
       build-iso \\
           --packages-dir ../repository \\
           --iso-version $ISO_VERSION \\
           $params.BUILD_ISO_EXTRA_PARAMETERS \\
"""
    }
  }

  sh 'ln -s builds/result/iso/latest iso'
  stash name: 'iso_dir', includes: 'iso/'
  archiveArtifacts artifacts: 'iso/'
}

def uploadArtifacts() {
  utils.setGithubStatus(
    triggeredRepoName, 'Uploading artifacts', 'PENDING')

  deleteDir()
  catchError {
    unstash 'repository_dir'
    unstash 'iso_dir'
  }

  String HTTP_URL_PREFIX = "http://$params.UPLOAD_SERVER_HOST_NAME"
  String RSYNC_URL_PREFIX =
    "$params.UPLOAD_SERVER_USER_NAME@$params.UPLOAD_SERVER_HOST_NAME:"
  String BUILDS_DIR_NAME = (
    params.UPLOAD_SERVER_BUILDS_DIR_PATH.tokenize('/').last())
  String BUILD_DIR_PATH =
    "$params.UPLOAD_SERVER_BUILDS_DIR_PATH/$buildInfo.BUILD_TIMESTAMP"
  String BUILDS_DIR_RSYNC_URL =
    "${RSYNC_URL_PREFIX}$params.UPLOAD_SERVER_BUILDS_DIR_PATH"
  String BUILD_DIR_HTTP_URL = "${HTTP_URL_PREFIX}$BUILD_DIR_PATH"
  String BUILD_DIR_RSYNC_URL = "${RSYNC_URL_PREFIX}$BUILD_DIR_PATH"

  buildInfo.REPOSITORY_FILE_URL = "$BUILD_DIR_HTTP_URL/hostos.repo"

  String jsonString = JsonOutput.prettyPrint(JsonOutput.toJson(buildInfo))
  echo "Writing build status file:\n" + jsonString
  writeFile file: 'STATUS', text: jsonString
  archiveArtifacts artifacts: 'STATUS'

  String repositoryConfiguration = """\
[hostos]
name=hostos
baseurl=$BUILD_DIR_HTTP_URL/repository
enabled=1
priority=1
gpgcheck=0
"""
  echo "Writing yum repository configuration file:\n" + repositoryConfiguration
  writeFile file: 'hostos.repo', text: repositoryConfiguration
  archiveArtifacts artifacts: 'hostos.repo'

  echo 'Creating remote build directory hierarchy'
  sh "mkdir -p $BUILDS_DIR_NAME/$buildInfo.BUILD_TIMESTAMP"
  utils.rsyncUpload("--recursive $BUILDS_DIR_NAME/", BUILDS_DIR_RSYNC_URL)

  echo 'Uploading artifacts'
  utils.rsyncUpload('STATUS', BUILD_DIR_RSYNC_URL)
  utils.rsyncUpload('--recursive repository', BUILD_DIR_RSYNC_URL)
  utils.rsyncUpload('--recursive iso', BUILD_DIR_RSYNC_URL)
  utils.rsyncUpload('hostos.repo', BUILD_DIR_RSYNC_URL)
}

return this