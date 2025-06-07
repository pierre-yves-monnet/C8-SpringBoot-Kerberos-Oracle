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


# Oracle

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

Create a new user
```shell
> CREATE USER oraidentity IDENTIFIED BY MySecurePassword123;
> GRANT CONNECT, RESOURCE TO oraidentity;
GRANT SELECT ON SYS.USER_SEQUENCES TO oraidentity;
GRANT SELECT ON SYS.ALL_SEQUENCES TO oraidentity;
GRANT CONNECT, RESOURCE TO oraidentity;

```
the user `oraidentity` will be used after by identity

# Kerberos

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: krb5-config
data:
  krb5.conf: |
    [libdefaults]
      default_realm = CAMUNDA.COM
    [realms]
      CAMUNDA.COM = {
        kdc = krb5-kdc.default.svc.cluster.local
        admin_server = krb5-kdc.default.svc.cluster.local
      }
    [domain_realm]
      .camunda.com = CAMUNDA.COM
      camunda.com = CAMUNDA.COM
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: krb5-kdc-data
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: krb5-kdc
spec:
  replicas: 1
  selector:
    matchLabels:
      app: krb5-kdc
  template:
    metadata:
      labels:
        app: krb5-kdc
    spec:
      containers:
        - name: krb5-kdc
          image: gcavalcante8808/krb5-server
          env:
            - name: KRB5_REALM
              value: CAMUNDA.COM
            - name: KRB5_KDC
              value: krb5-kdc.default.svc.cluster.local
            - name: KRB5_PASS
              value: adminpassword
          ports:
            - containerPort: 88
              name: kerberos
            - containerPort: 464
              name: kpasswd
            - containerPort: 749
              name: kadmin
          volumeMounts:
            - name: krb5-config
              mountPath: /etc/krb5.conf
              subPath: krb5.conf
            - name: krb5-data
              mountPath: /var/lib/krb5kdc
      volumes:
        - name: krb5-config
          configMap:
            name: krb5-config
        - name: krb5-data
          persistentVolumeClaim:
            claimName: krb5-kdc-data
---
apiVersion: v1
kind: Service
metadata:
  name: krb5-kdc
spec:
  selector:
    app: krb5-kdc
  ports:
    - name: kerberos
      port: 88
      targetPort: 88
    - name: kpasswd
      port: 464
      targetPort: 464
    - name: kadmin
      port: 749
      targetPort: 749

```
Create the kubernetes resources

```shell
$ kubectl apply -f src/main/resources/kerberos.yaml -n camunda
```

This creates a `krb5.conf` information, accessible in the cluster

Information are

| Parameter     | Value       | 
|---------------|-------------| 
| REAM          | CAMUNDA.COM |
| domain_realm  | CAMUNDA.COM |

Check the log
```shell
$ kubectl get pods
$ kubectl logs -f krb5-kdc-84fb77fc6c-mt86g
/docker-entrypoint.sh: line 21: can't create /etc/krb5.conf: Read-only file system
No Krb5 database found. Creating one now.
Initializing database '/var/lib/krb5kdc/principal' for realm 'CAMUNDA.COM',
master key name 'K/M@CAMUNDA.COM'
Authenticating as principal root/admin@CAMUNDA.COM with password.
No policy specified for admin/admin@CAMUNDA.COM; defaulting to no policy
Principal "admin/admin@CAMUNDA.COM" created.
2025-06-06 23:41:38,006 CRIT Server 'inet_http_server' running without any HTTP authentication checking
````
The user used in Oracle must be referenced in Kerberos


```shell
$ kubectl exec -it krb5-kdc-84fb77fc6c-mt86g -- kadmin.local
> addprinc oracle
Enter password for principal "oracle@CAMUNDA.COM":
Password is MySecurePassword123
```

## Create a keytab file

```shell
$ kubectl exec -it krb5-kdc-84fb77fc6c-mt86g -- /bin/sh
/ # kadmin.local
kadmin.local: addprinc -randkey myservice/myhost.camunda.com@CAMUNDA.COM
No policy specified for myservice/myhost.camunda.com@CAMUNDA.COM; defaulting to no policy
Principal "myservice/myhost.camunda.com@CAMUNDA.COM" created.

kadmin.local: ktadd -k /tmp/myservice.keytab myservice/myhost.camunda.com@CAMUNDA.COM
Entry for principal myservice/myhost.camunda.com@CAMUNDA.COM with kvno 2, encryption type aes256-cts-hmac-sha1-96 added to keytab WRFILE:/tmp/myservice.keytab.
Entry for principal myservice/myhost.camunda.com@CAMUNDA.COM with kvno 2, encryption type aes128-cts-hmac-sha1-96 added to keytab WRFILE:/tmp/myservice.keytab.

```



## Oracle Database
1. Ensure a service principal name (SPN) is registered for the database service in your Kerberos realm.

2. use the keytab file

3. Configure Oracle Net Server



# Identity

## Download the driver

The first step consists of downloaded the jdbc drivers. The driver is not publicetly accessible, so it must be downloaded from somewhere else.
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
## Direct access to Oracle

Specify this environment variable for a direct access
```yaml
  env:
    - name: SPRING_PROFILES_ACTIVE
      value: oidc
    - name: SPRING_DATASOURCE_URL
      value: jdbc:oracle:thin:@//oracle-db:1521/XE
    - name: SPRING_DATASOURCE_DRIVER_CLASS_NAME
      value: oracle.jdbc.OracleDriver
    - name: SPRING_DATASOURCE_USERNAME
      value: oraidentity
    - name: SPRING_DATASOURCE_PASSWORD
      value: MySecurePassword123
    - name: SPRING_JPA_HIBERNATE_DDL-AUTO
      value: update
```

TO DO: error, SpringBoot can't create the table (but it is connected)

## Using Kerberos


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
