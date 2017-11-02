From [here](https://gist.github.com/rmcgibbo/3433798):

```
$ curl -u vsp-jenkins-bot https://api.github.com/authorizations -d '{"scopes":["repo"]}'
where vsp-jenkins-bot is the github username I'm using for the comments to be listed under.
```

Alt...

```
curl -u hartzell http://github.com/authorizations -d '{"scopes":["repo"], "note": "curl test"}'
```
