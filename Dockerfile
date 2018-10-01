# USE THE LTS version...
# Consider using -slim docker container...
FROM jenkins/jenkins:2.138.1-alpine
MAINTAINER George Hartzell <hartzell@alerce.com>

# Install these plugins
# Can pin plugin versions as `plugin:version`
# or just refer to them as `plugin` to get current
# at `docker build` time
RUN /usr/local/bin/install-plugins.sh  \
    credentials:2.1.18 \
    credentials-binding:1.16 \
    files-found-trigger:1.5 \
    git:3.9.1 \
    github:1.29.2 \
    ghprb:1.42.0 \
    jdk-tool:1.1 \
    job-dsl:1.70 \
    junit:1.26.1 \
    mailer:1.21 \
    matrix-auth:2.3 \
    ssh-credentials:1.14 \
    ssh-slaves:1.28.1 \
    tap:2.2.1

EXPOSE 8080

### no gefingerpoken below this line ###

# Skip Jenkins normal first-time setup stuff
ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false -Dmail.smtp.starttls.enable=true

# Disable JNLP since we use ssh (Jenkins magic, sigh...)
ENV JENKINS_SLAVE_AGENT_PORT=-1

# Install our setup script
COPY setup.groovy /usr/share/jenkins/ref/init.groovy.d/

VOLUME /var/jenkins_home
VOLUME /cimr_config.yaml
