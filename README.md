# C8-SpringBoot-Kerberos-Oracle
C8-SpringBoot-Kerberos-Oracle

# introduction
This project gives the procedure to connect an Oracle database to Idenity, and then use Kerberos to connect this database.

* First, an oracle pod is created in the cluster, and a user `oraidentity` is created in the database
* Second, a Kerberos pod is deployed, and configure to march the `oraidentity` user
* Last, identity is configured to use the user define in Kerberos to connect


# Pre requisite

A cluster is created.

In the cluster, a namespace camunda is created

```shell
$ kubectl create namespace camunda
```

# Identity and Oracle

## Oracle

Check the [src/main/resources/oracle.yaml](src/main/resources/oracle.yaml) file

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: oracle-data
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
---
apiVersion: v1
kind: Service
metadata:
  name: oracle-db
spec:
  type: ClusterIP
  ports:
    - name: sql
      port: 1521
      targetPort: 1521
    - name: em
      port: 5500
      targetPort: 5500
  selector:
    app: oracle-db
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: oracle-db
spec:
  replicas: 1
  selector:
    matchLabels:
      app: oracle-db
  template:
    metadata:
      labels:
        app: oracle-db
    spec:
      securityContext:
        fsGroup: 54321
      containers:
        - name: oracle-db
          image: gvenzl/oracle-xe:21-slim
          ports:
            - containerPort: 1521
            - containerPort: 5500
          env:
            - name: ORACLE_PASSWORD
              value: MySecurePassword123
          volumeMounts:
            - mountPath: /opt/oracle/oradata
              name: oracle-pv
            - mountPath: /tmp
              name: tmp-volume
      volumes:
        - name: oracle-pv
          persistentVolumeClaim:
            claimName: oracle-data
        - name: tmp-volume
          emptyDir: {}
      resources:
        requests:
          ephemeral-storage: "1Gi"
        limits:
          ephemeral-storage: "2Gi"

```

Create the kubernetes resources

```shell
$ kubectl apply -f src/main/resources/oracle.yaml -n camunda
```

Oracle is started with a user `app`, password `MySecurePassword123`

Check it via sqlplus
```shell
pym@Edrahil:$ kubectl exec -it oracle-db-c5c4ff455-n7fpb -- bash
$ sqlplus system/MySecurePassword123@//localhost:1521 as sysdba
> SELECT username FROM dba_users;
```

> Note: if sqlplus ask again a login password, give system then MySecurePassword123
> 
Create a new user
```shell
> CREATE USER oraidentity IDENTIFIED BY MySecurePassword123;
> GRANT CONNECT, RESOURCE TO oraidentity;
GRANT SELECT ON SYS.USER_SEQUENCES TO oraidentity;
GRANT SELECT ON SYS.ALL_SEQUENCES TO oraidentity;
GRANT CONNECT, RESOURCE TO oraidentity;
ALTER USER oraidentity QUOTA UNLIMITED ON USERS;


```
the user `oraidentity` will be used after by identity.



## Identity

Check the documentation:
https://docs.camunda.io/docs/self-managed/identity/configuration/alternative-db/?oracle-config=valuesYaml&mssql-config=valuesYaml

### Download the driver

The first step consists of downloaded the jdbc drivers. The driver is not publicly accessible, so it must be downloaded from somewhere else.
It is saved in the GitHub repository `driver` for test reason.

```yaml
identity:
  enabled: true
  keycloak:
    url:
      protocol: "http"
      host: "camunda-keycloak-0"
      port: "80"
  postgresql:
    enabled: false
  initContainers:
    - name: download-jdbc-driver
      image: curlimages/curl:latest
      imagePullPolicy: Always
      command:
        - sh
        - -c
        - curl -L -o /extraDrivers/ojdbc17.jar https://raw.githubusercontent.com/pierre-yves-monnet/C8-SpringBoot-Kerberos-Oracle/refs/heads/main/driver/ojdbc17.jar

      volumeMounts:
        - name: jdbcdrivers
          mountPath: /extraDrivers
      securityContext:
        runAsUser: 1000
        runAsNonRoot: true
  extraVolumeMounts:
    - name: jdbcdrivers
      mountPath: /extraDrivers
    
  extraVolumes:
    - name: jdbcdrivers
      emptyDir: { }
```

To verify that the driver is correctly downloaded, add this initcontainer
```yaml
    - name: busybox
      image: busybox
      command: [ "sleep", "36000" ]
      volumeMounts:
        - name: jdbcdrivers
          mountPath: /extraDrivers
      securityContext:
        runAsUser: 1000
        runAsNonRoot: true

```

then, jump in the container
```shell
pym@Edrahil:$ kubectl get pods
camunda-identity-7b7778d454-6zhlz        0/1     Init:1/2           0            2m59s

pym@Edrahil:$ kubectl exec -it camunda-identity-7b7778d454-6zhlz -c busybox -- /bin/sh
$ ls -al /extraDrivers
-rw-r--r--    1 1000     1001       7371630 Jun  6 21:29 ojdbc17.jar

```
### Direct access to Oracle

Specify this environment variable for a direct access.

```yaml
  env:
     - name: LOGGING_LEVEL_ROOT
       value: DEBUG
     - name: SPRING_PROFILES_ACTIVE
       value: oidc
     - name: MULTI_TENANCY_ENABLED
       value: "true"
     - name: SPRING_JPA_DATABASE
       value: oracle
     - name: SPRING_DATASOURCE_URL
       value: jdbc:oracle:thin:@//oracle-db:1521/XE
     - name: SPRING_DATASOURCE_DRIVER_CLASS_NAME
       value: oracle.jdbc.OracleDriver
     - name: SPRING_DATASOURCE_USERNAME
       value: oraidentity
     - name: SPRING_DATASOURCE_PASSWORD
       value: MySecurePassword123

```


### Check oracle

```shell
pym@Edrahil:$ kubernetes exec -it oracle-db-c5c4ff455-gf8tp -- bash

$ sqlplus oraidentity/MySecurePassword123@//oracle-db:1521/XE

SQL> SELECT table_name FROM user_tables;

TABLE_NAME
--------------------------------------------------------------------------------
HTE_MIGRATIONS
ACCESS_RULES
ACCESS_RULES_TENANTS
GROUP_ROLES
GROUPS
GROUPS
MAPPING_RULES
MAPPING_RULES_APPLIED_ROLES
MAPPING_RULES_APPLIED_TENANTS
MEMBERSHIPS
MIGRATIONS
PERMISSIONS

TABLE_NAME
--------------------------------------------------------------------------------
RESOURCE_AUTHORIZATIONS
RESOURCES
ROLES
ROLES_PERMISSIONS
TENANTS


16 rows selected.
```


# Kerberos, Oracle and Identity

This procedure starts from a clean cluster.

```shell
kubernetes create namespace camunda
```

## Kerberos server

Create first the Kerberos server.

```shell
kubernetes apply -f src/main/resources/kerberos/krb-kerberos.yaml -n camunda
```








------------------ in progress





## create sqlnet.ora and krb5.conf

Both configuration files will be mounted on Oracle under /opt/oracle/product/23ai/dbhomeFree/network/admin/.

The sqlnet.ora reference `krb5.conf` file

```shell
    SQLNET.KERBEROS5_CONF=/opt/oracle/product/23ai/dbhomeFree/network/admin/krb5.conf
```
sqlnet.ora refeence 
```
SQLNET.KERBEROS5_KEYTAB=/tmp/keytabs/keytab
```
So, Oracle will now mount the two files in the correct directory

```shell
kubernetes apply -f src/main/resources/kerberos/kdc-sqlnet-krb5.yaml -n camunda
```





kdc-keytab-pvc

This configuration 
```
javax.security.auth.useSubjectCredsOnly=false
java.security.krb5.conf=/tmp/krb5.conf
java.security.auth.login.config=/tmp/jaas.conf
```





Put the Oracle Kerberos keytab file (for your app principal) inside your app container/pod, e.g., /etc/security/keytabs/app.keytab

Make sure your app has a krb5.conf configuration file that points to your KDC and realm info (can be mounted in container or in classpath)

4. Configure Java Kerberos system properties
   Set Java system properties to enable Kerberos and point to the config and keytab files.

Example in application.properties or your container environment variables:

properties
Copy
# JVM args (can be passed via JAVA_OPTS or similar)
-Djava.security.krb5.conf=/path/to/krb5.conf
-Djavax.security.auth.useSubjectCredsOnly=false
-Djava.security.auth.login.config=/path/to/jaas.conf

Example jaas.conf for Kerberos login:

text
Copy
com.sun.security.jgss.krb5.initiate {
com.sun.security.auth.module.Krb5LoginModule required
useKeyTab=true
keyTab="/etc/security/keytabs/app.keytab"
principal="appuser@REALM.COM"
storeKey=true
debug=true;
};
Pass this JAAS file location with:

bash
Copy
-Djava.security.auth.login.config=/path/to/jaas.conf


Configure Spring Boot datasource
Your spring.datasource.url will be a standard Oracle JDBC URL, but with Kerberos auth enabled via Oracle JDBC driver properties.

Example JDBC URL (using Kerberos):

bash
Copy
jdbc:oracle:thin:@//oracle-host:1521/service_name
Additional properties may be required, for example:

properties
Copy
spring.datasource.url=jdbc:oracle:thin:@//oracle-host:1521/service_name
spring.datasource.username=appuser
spring.datasource.password=  # usually empty or unused with Kerberos
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
spring.datasource.hikari.data-source-properties.oracle.net.authentication_services=(KERBEROS5)
