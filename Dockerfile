# USE THE LTS version...
FROM jenkins/jenkins:2.116
MAINTAINER George Hartzell <hartzell@alerce.com>

# Install these plugins
# Can pin plugin versions as `plugin:version`
# or just refer to them as `plugin` to get current
# at `docker build` time
RUN /usr/local/bin/install-plugins.sh  \
    credentials:2.1.16                 \
    credentials-binding:1.16           \
    git:3.8.0                          \
    github:1.29.0                      \
    ghprb:1.40.0                       \
    jdk-tool:1.1                       \
    job-dsl:1.69                       \
    junit:1.24                         \
    mailer:1.21                        \
    matrix-auth:2.2                    \
    ssh-credentials:1.13               \
    ssh-slaves:1.26                    \
    tap:2.2.1

EXPOSE 8080

### no gefingerpoken below this line ###

# Skip Jenkins normal first-time setup stuff
ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false

# Disable JNLP since we use ssh (Jenkins magic, sigh...)
ENV JENKINS_SLAVE_AGENT_PORT=-1

# Install our setup script
COPY setup.groovy /usr/share/jenkins/ref/init.groovy.d/

VOLUME /var/jenkins_home
VOLUME /cimr_config.yaml
