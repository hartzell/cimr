//
// This isn't the prettiest groovy code, but it's straightforward.
// I have no idea how to do automated tests against a Jenkins
// instance...
//
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import hudson.model.*
import hudson.plugins.sshslaves.*
import hudson.plugins.sshslaves.verifiers.*
import hudson.security.*
import hudson.security.csrf.DefaultCrumbIssuer
import hudson.slaves.*
import hudson.util.Secret
import java.lang.reflect.Field
import jenkins.*
import jenkins.model.*
import jenkins.security.s2m.AdminWhitelistRule
import org.jenkinsci.plugins.ghprb.*
import org.jenkinsci.plugins.github.config.GitHubPluginConfig
import org.jenkinsci.plugins.github.config.GitHubServerConfig
import org.jenkinsci.plugins.plaincredentials.impl.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor

import javaposse.jobdsl.plugin.*
import javaposse.jobdsl.dsl.*

// Quick and dirty check that the config looks valid.  Just checks that
// expected fields are not null.  Beyond that, YMMV.
def _validate = { c ->
  // The config must provide values for these fields
  ['admin_username', 'admin_password', 'git_key_private_part',
   'git_key_username', 'slaves_key_private_part', 'slaves_key_username',
   'github_api_token', 'github_api_url', 'git_config_email', 'git_config_name',
   'email_reply_to_address', 'email_smtp_host', 'jenkins_public_url',
  ].each {
    // null and empty string are false...
    assert (c[it]) : it + " must not be null"
  }

  // Each slave must have these fields
  c.slaves.each { slave ->
    ['name', 'description', 'working_dir',
     'executor_count', 'label', 'address',
     'host_key'
    ].each {
      // null and empty string are false...
      assert (slave[it])  : "slave " + it + " must not be null"
    }
  }
}

// Takes a YAML string as input, loads it, merges a few overridable bits,
// calls the validator to make sure it smells right, does a bit of cleanup
// then returns the data structure.
def configLoader = { y ->
  Constructor cons = new Constructor()
  Yaml yamlLoader = new Yaml(cons)
  def c = yamlLoader.load y

  // These config values can be overridden by the environment
  def env = System.getenv()
  if ( env.containsKey('JENKINS_ADMIN_PASSWORD')) {
    c.admin_password = env.JENKINS_ADMIN_PASSWORD
  }
  if ( env.containsKey('JENKINS_GIT_PRIVATE_KEY')) {
    c.git_key_private_part = env.JENKINS_GIT_PRIVATE_KEY
  }
  if ( env.containsKey('JENKINS_SLAVES_PRIVATE_KEY')) {
    c.slaves_key_private_part = env.JENKINS_SLAVES_PRIVATE_KEY
  }
  if ( env.containsKey('JENKINS_GITHUB_TOKEN')) {
    c.github_api_token = env.JENKINS_GITHUB_TOKEN
  }

  _validate(c)

  // make sure the url ends in a /, else mysterious sad things happen
  if (! (c.jenkins_public_url ==~ /.*\//)) {
    c.jenkins_public_url = c.jenkins_public_url + '/'
  }

  return c
}

def yamlString = new File('cimr_config.yaml').text
def c = configLoader(yamlString)

def instance = Jenkins.getInstance()

////////////////////////////////////////////////////////////////
// Set up security and add users
//

// false => don't allow signups
instance.setSecurityRealm(new HudsonPrivateSecurityRealm(false))
// enable the global matrix authorization strategy
instance.setAuthorizationStrategy(new GlobalMatrixAuthorizationStrategy())

// create a user from JENKINS_{USER,PASS} environment variables
def user = instance.getSecurityRealm().createAccount(c.admin_username, c.admin_password)
user.save()

// You can add more users if you'd like....
// user = instance.getSecurityRealm().createAccount('mickey', env.MICKEY_PASSWORD)
// user.save()

// The Jenkins.* things change checkboxes in the 'Overall' part of the matrix
// .Item.* things control checkboxes in the 'Job' part of the matrix
instance.getAuthorizationStrategy().add(Jenkins.ADMINISTER, c.admin_username)

// and perhaps make your additional users (see above) into admins
// instance.getAuthorizationStrategy().add(Jenkins.ADMINISTER, 'mickey')

// The anonymous use needs all three of these so that the
// `http://<SERVER>/job/<JOBNAME>/build` API endpoint works.
// But it also give anonymous access to your jenkins.  Choose your poison.
// instance.getAuthorizationStrategy().add(hudson.model.Hudson.READ, 'anonymous')
// instance.getAuthorizationStrategy().add(hudson.model.Item.READ, 'anonymous')
// instance.getAuthorizationStrategy().add(hudson.model.Item.BUILD, 'anonymous')

// disable tcp port for JNLP agents.
instance.setSlaveAgentPort(-1)

// Disable all of the Agent protocols too (fewer warnings) by passing in 
// an empty set of 'em.
def protos = [] as Set
instance.setAgentProtocols(protos)

// uncheck the 'Enable CLI over Remoting' box
instance.getDescriptor("jenkins.CLI").get().setEnabled(false)

// enable "Agent -> Master access control"
instance.injector.getInstance(AdminWhitelistRule.class).setMasterKillSwitch(false)

// Disable JobDSL security.  Seems like this is getting the attribute
instance.injector.getInstance(GlobalJobDslSecurityConfiguration.class).setUseScriptSecurity(false)

// Enable CuRF protection.  
// Might require starting with -Dhudson.security.csrf.requestfield=Jenkins-Crumb
// See JENKINS-23793
// instance.crumbIssuer = new DefaultCrumbIssuer(true)
// https://gist.github.com/ivan-pinatti/7d8a877aff42350f16fcb1eb094818d9
instance.setCrumbIssuer(new DefaultCrumbIssuer(true))

instance.save()

////////////////////////////////////////////////////////////////
// Set up some credentials
//
// The id's that we assign to the credentials matter, in so far as
// we'll refer to them by id while setting up other bits.
//

def store = instance.getExtensionList('com.cloudbees.plugins.credentials.SystemCredentialsProvider')[0].getStore()

////////////////////////////////
// SSH keys
// - args are scope, id to assign to key, username that key works with,
//   a private key source (path to key), passphrase, and description
//

// An SSH key for master<->slave connections
def jenkinsSlavesKey = new BasicSSHUserPrivateKey(
  CredentialsScope.GLOBAL,
  "slaves-key",
  c.slaves_key_username,
  new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(c.slaves_key_private_part),
  "",
  "master<->slave ssh private key"
)
store.addCredentials(Domain.global(), jenkinsSlavesKey)

// An SSH key for git operations using the Git Scm plugin
def gitKey = new BasicSSHUserPrivateKey(
  CredentialsScope.GLOBAL,
  "git-key",
  c.git_key_username,
  new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(c.git_key_private_part),
  "",
  "git ssh private key"
)
store.addCredentials(Domain.global(), gitKey)

////////////////////////////////
// Secret Text credentials
//

// A "Secret Text" credential for the GitHub and GHPRB plugins,
// containing a GitHub token with repo:status and admin:repo_hook
// scopes.  Might need more if you get fancier.
// https://support.cloudbees.com/hc/en-us/articles/224621668-GitHub-User-Scopes-and-Organization-Permission
githubAccessToken = new StringCredentialsImpl(
  CredentialsScope.GLOBAL,
  "github-api-token",
  "Token for the github/ghprb plugins",
  Secret.fromString(c.github_api_token)
)
store.addCredentials(Domain.global(), githubAccessToken)

////////////////////////////////
// Username and Password credentials
//
jenkinsUsernameAndPassword = new UsernamePasswordCredentialsImpl(
  CredentialsScope.GLOBAL,
  "jenkins-user-and-password",
  "Username and password to use for talking to jenkins",
  c.admin_username,
  c.admin_password
)
store.addCredentials(Domain.global(), jenkinsUsernameAndPassword)

////////////////////////////////////////////////////////////////
// Various Global Configuration bits

// Run a single executor on the master
instance.setNumExecutors(1)

// Set the URL for the site (needed to make BUILD_URL env var avail in
// build steps ) and administrator email.
jlc = JenkinsLocationConfiguration.get()
// Url must have trailing '/' or ghprb borks
jlc.setUrl(c.jenkins_public_url)
jlc.setAdminAddress(c.email_reply_to_address)
jlc.save()

// Global git scm configuration (name, email), override-able per job
def gitDesc = instance.getDescriptor("hudson.plugins.git.GitSCM")
gitDesc.setGlobalConfigEmail(c.git_config_email)
gitDesc.setGlobalConfigName(c.git_config_name)
gitDesc.save()

// Disable the built-in ssh daemon
def sshDesc = instance.getDescriptor("org.jenkinsci.main.modules.sshd.SSHD")
sshDesc.setPort(-1)
sshDesc.getActualPort()
sshDesc.save()

// set up the outbound mailer
def mailDesc = instance.getDescriptor("hudson.tasks.Mailer")
mailDesc.setSmtpHost(c.email_smtp_host)
mailDesc.setReplyToAddress(c.email_reply_to_addr)
mailDesc.save()

// Set up the GitHub Plugin
def ghDesc = Jenkins.instance.getDescriptorByType(org.jenkinsci.plugins.github.config.GitHubPluginConfig)
def ghConf = new GitHubServerConfig('github-api-token')
ghConf.setApiUrl(c.github_api_url)
ghConf.setManageHooks(true)
def list = new ArrayList<GitHubServerConfig>(1)
list.add(ghConf)
ghDesc.setConfigs(list)
ghDesc.save()

// Set up the Github Pull Request Builder Plugin
//    https://gist.github.com/kpettijohn/e294c50e29ca4e8a329e
def ghprbDesc = Jenkins.instance.getDescriptorByType(org.jenkinsci.plugins.ghprb.GhprbTrigger.DescriptorImpl.class)
Field auth = ghprbDesc.class.getDeclaredField("githubAuth")
auth.setAccessible(true)

githubAuth = new ArrayList<GhprbGitHubAuth>(1)
githubAuth.add(
  // github url, jenkins url, credential id, description, id, secret
  new GhprbGitHubAuth(c.github_api_url,
                      c.jenkins_public_url, "github-api-token",
                      "github auth info", "id-for-github", null)
)
auth.set(ghprbDesc, githubAuth)

// Set a value to use when Jenkins asks the admins if it's
// ok to run a build from a "stranger".  This field has no
// default value, so if it's not set here, Jenkins accesses a
// null pointer and becomes sad.
// See https://github.com/janinko/ghprb/pull/452 for details.
Field testPlease = ghprbDesc.class.getDeclaredField("requestForTestingPhrase")
testPlease.setAccessible(true)
testPlease.set(ghprbDesc, "Can one of the admins verify this patch?")

// Here's another example of using reflection to access one of the
// private fields of the GhprbTrigger.  Ick....
//
// Field okPhrase = ghprbDesc.class.getDeclaredField("okToTestPhrase")
// okPhrase.setAccessible(true)
// okPhrase.set(ghprbDesc, "gesundheit")

ghprbDesc.save()

// Configure some slaves, adjust to taste...
// - Args are: name, description, remote directory, # of executors,
//   availability, label, launcher, retention strategy, node
//   properties
// - Launcher args: host, port, credentials id, jvm options, java
//   path, prefix start slave command, suffix start slave command,
//   launch timeout, max retries, retry wait time,
//   host key strategy
c.slaves.each { s ->
  strategy = new ManuallyProvidedKeyVerificationStrategy(s.host_key)
  instance.addNode(
    new DumbSlave(
      s.name,
      s.description,
      s.working_dir,
      s.executor_count.toString(), // DumbSlave expects a string...
      Node.Mode.NORMAL,
      s.label,
      new SSHLauncher(s.address,22,"slaves-key","","","","",0,0,0, strategy),
      new RetentionStrategy.Always(),
      new LinkedList()
    )
  )
}

if (c.jobs) {
  // Empty, for now
  Map<String, String> envVars = [
    :
  ]
  // The File is only used to discover the working dir...
  JobManagement jm = new JenkinsJobManagement(System.out, envVars, new File('.'))
  loader = new DslScriptLoader(jm)

  c.jobs.each{  j ->
    loader.runScript(j)
  }
}

// If there's a seed job, run it.
def job = hudson.model.Hudson.instance.getItem("seed") 
if (job) {
    job.scheduleBuild()
}
