# Zephyr data processor (0.1.0)

Zephyr data processor synchronizes the test in th2 with Zephyr test.
It searches for events that match format in the configuration and updates (or create new) executions.


## Event tree

When the data processor finds the event it tries to extract information about folder, version and cycle.
It checks the event tree. It should have the following format

```
Root event with name `version|CycleName|Any other information you want`
|- Sub event with folder name. _NOTE: if there are several sub events the closest one to the issue event will be taken_
   |- TEST-1253  // the issue event. Its name must match the format in the configuration
```

## Configuration

There is an example of full configuration for the data processor

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Box
metadata:
  name: zephyr-processor
spec:
  image-name: ghcr.io/th2-net/th2-data-processor-zephyr
  image-version: 0.1.0
  type: th2-act
  pins:
    - name: server
      connection-type: grpc
    - name: to_data_provider
      connection-type: grpc
  custom-config:
    connection:
      baseUrl: "https://your.jira.address.com"
      jira:
        username: "jira-user"
        key: "you password" # or api key
    dataService:
      name: "ZephyrService"
      versionMarker: "0.0.1"
    syncParameters:
      issueFormat: "QAP_\\d+"
      delimiter: '|'
      statusMapping:
        SUCCESS: PASS
        FAILED: WIP
      jobAwaitTimeout: 1000
      relatedIssuesStrategies:
        - type: linked
          trackLinkedIssues:
              - linkName: "is cloned by"
                whitelist:
                    - projectKey: "P1"
                      issues:
                          - TEST-1
                          - TEST-2
                          - TEST-3
                    - projectName: "P2 Project"
                      issues:
                          - TEST-1
                          - TEST-2
                          - TEST-4
    httpLogging:
      level: INFO
  extended-settings:
    service:
      enabled: true
      type: ClusterIP
      endpoints:
        - name: 'grpc'
          targetPort: 8080
    envVariables:
      JAVA_TOOL_OPTIONS: '-XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport -XX:MaxRAMPercentage=85'
    resources:
      limits:
        memory: 250Mi
        cpu: 200m
      requests:
        memory: 100Mi
        cpu: 50m
```

### Parameters description

#### connection

Contains information about the endpoint ot connect

+ baseUrl - url to the Jira instance
+ jira - block contains credentials to connect to Jira
    + username - the Jira username
    + key - the Jira password or API key for authentication

#### dataService

Contains information about name and version for current data service. It is used to mark the events that already processed by this service

+ name - the data service name
+ versionMarker - the data service version

#### syncParameters

Contains parameters for synchronization with Zephyr

+ **issueFormat** - the regular expression to match the event that corresponds to the issue
+ **delimiter** - the delimiter to use to extract version and cycle from the root event
+ **statusMapping** - mapping between event status and status in Zephyr. **NOTE: mapping for SUCCESS and FAILED event statuses is required**
+ **jobAwaitTimeout** - the timeout to await the job for adding test to a cycle/folder
+ **relatedIssuesStrategies** - configures the strategies to find the additional issues related to the currently processing one.
  They will be updated using the version, cycle and folder for the current issue.
  
##### Strategies

All strategies should be configured in the following way:
```yaml
relatedIssuesStrategies:
  - type: <strat type>
    strategySpecificParameters:
    #...
  - type: <another strat>
    strategySpecificParameters:
    #...
```

###### **linked**

Finds the issues that are linked to the current one. Uses configuration to decide which links should be taken into account.
Configuration example:
```yaml
relatedIssuesStrategies:
  - type: linked
    trackLinkedIssues:
        - linkName: "is cloned by"
          whitelist:
              - projectKey: P1
                issues:
                    - TEST-3
              - projectName: "P2 Project"
                issues:
                    - TEST-1
                    - TEST-2
        - linkName: "is similar to"
          direction: INWARD # or OUTWARD
          whitelist:
              - projectKey: P1
                issues:
                    - TEST-42
        - linkName: "is duplicated by"
          disable: true
          whitelist:
              - projectKey: P3
                issues:
                    - *
```

###### _Parameters_

+ **trackLinkedIssues** - the list of links to track
    + **linkName** - the link name to track. Can be found in the JIRA in _Issue Links_/_Linked Issues_ block. **Required parameter**
    + **direction** - the link direction. Can be used to clarify which link to use if the **linkName** for both directions is the same.
        Possible values are: **INWARD** - another issue is linked to us. **OUTWARD** - we are linked to another issue.
    + **disable** - you can disable the tracking of the specific link in the configuration
    + **whitelist** - the list contains information about which issues should be taken to follow and for which projects.
      _E.g. we have issue A-42 that is cloned by the issue B-42 (project B).
      If the issue A-42 is in the whitelist for project **B** this link will be followed_
      + **projectKey** - project identifier. It is inner key for JIRA
      + **projectName** - project name in JIRA. Can be used instead of **projectKey**.
        _NOTE: only one of the parameters **projectKey** or **projectName** can be used_
      + **issues** - the set of issues for with the link with specified type should be tracked if the linked issues belongs to the specified project.
        If you want to allow to track any issue which has links with specified type and linked issue belongs to the specified project
        you can use wildcard mark `*`

#### httpLogging

Contains parameters to set up the Logging for inner HTTP clients that are used to connect to the Jira and Zephyr

+ level - level logging for HTTP client. Available levels: **ALL**, **HEADERS**, **BODY**, **INFO**, **NONE**

## Links example

The **data processor zephyr** requires the link to the **data provider** working in gRPC mode. Link example:

```yaml
apiVersion: th2.exactpro.com/v1
kind: Th2Link
metadata:
  name: zephyr-service-links
spec:
  boxes-relation:
    router-grpc:
    - name: data-service-zephyr-to-data-provider
      from:
        strategy: filter
        box: zephyr-service
        pin: to_data_provider
      to:
        service-class: com.exactpro.th2.dataprovider.grpc.DataProviderService
        strategy: robin
        box: data-provider
        pin: server
```

# Changes

## v0.1.0

### Added

+ Strategies for finding related issues
+ Create strategy for following issue links


# Useful links

+ th2-common - https://github.com/th2-net/th2-common-j