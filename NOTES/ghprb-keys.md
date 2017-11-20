From https://github.com/jenkinsci/ghprb-plugin/issues/582#issuecomment-344650904

> I had the same problem and solved it with the configure block. Add this at the bottom of your job definition so that it can traverse the structure created by the ghprb wrapper.

```groovy
  // The configure block gives access to the raw XML.
  // This is needed here because the ghprb DSL extension doesn't
  // provide access to the credentials to use.
  configure { node ->
    def triggers = node / 'triggers'
    // Iterate and find the right trigger node so that this
    // doesn't depend on the version of the ghprb plugin.
    triggers.children().each { trigger ->
      if (trigger.name() == 'org.jenkinsci.plugins.ghprb.GhprbTrigger') {
        // This adds the <gitHubAuthId/> tag with your credentials
        def gitHubAuthId = trigger / 'gitHubAuthId'
        gitHubAuthId.setValue('uuid of your credentials')
      }
    }
  }
```

