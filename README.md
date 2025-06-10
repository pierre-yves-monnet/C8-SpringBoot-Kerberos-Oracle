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
         storage: 1Gi
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
               # image: gvenzl/oracle-xe:21-slim
              image: container-registry.oracle.com/database/free:latest
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
   postgresql:
      enabled: false
      #  https://download.oracle.com/otn-pub/otn_software/jdbc/237/ojdbc17.jar.
   initContainers:
      - name: download-jdbc-driver
        image: curlimages/curl:latest
        imagePullPolicy: Always
        command:
           - sh
           - -c
           - curl -L -o /extraDrivers/ojdbc17.jar https://download.oracle.com/otn-pub/otn_software/jdbc/237/ojdbc17.jar

        volumeMounts:
           - name: jdbcdrivers
             mountPath: /extraDrivers
        securityContext:
           runAsUser: 1000
           runAsNonRoot: true
   command:
      - /bin/sh
      - -c
      - java -cp "/extraDrivers/ojdbc17.jar:/app/identity.jar" org.springframework.boot.loader.launch.JarLauncher
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
      - name: SPRING_JPA_SHOW-SQL
        value: "true"
      - name: SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL
        value: "true"
      - name: LOGGING_LEVEL_ORG_HIBERNATE_TYPE_DESCRIPTOR_SQL_BASICBINDER
        value: TRACE
      - name: SPRING_DATASOURCE_USERNAME
        value: oraidentity
      - name: SPRING_DATASOURCE_PASSWORD
        value: MySecurePassword123
      - name: SPRING_JPA_HIBERNATE_DDL-AUTO
        value: update         
      - name: JAVA_OPTS
        value: "-Dloader.path=/extraDrivers"

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




The kerberos file is
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: krb5-keytab-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100M
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: krb5-data-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100M
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: krb5-conf
data:
  krb5.conf: |
    [libdefaults]
      forwardable = true
      default_realm = EXAMPLE.COM
      supported_enctypes = aes256-cts-hmac-sha1-96:normal aes128-cts-hmac-sha1-96:normal
    
    [realms]
      EXAMPLE.COM = {
        kdc = kdc-kadmin
        supported_enctypes = aes256-cts-hmac-sha1-96:normal aes128-cts-hmac-sha1-96:normal    
       }
    
    [domain_realm]
      .example.com = EXAMPLE.COM
      example.com = EXAMPLE.COM

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
        - name: init
          image: debian:bullseye
          command: [ "/bin/sh", "-c" ]
          args:
            - |
              echo "------- Start initialisation" && \              
              export DEBIAN_FRONTEND=noninteractive && \
              ls -al /krb5-conf && \
              cp /krb5-conf/krb5.conf /etc/krb5.conf && \
              echo "------- after copy krb5.conf" && \
              ls -al /etc/krb5.conf && \
              cat /etc/krb5.conf && \
              echo "------- apt get " && \
              apt-get update  && \
              echo "------- install -y krb5-kdc" && \
              apt-get install -y krb5-kdc krb5-admin-server && \
              echo "------- create /etc/krb5kdc/kdc.conf";
              
              tee /etc/krb5kdc/kdc.conf <<EOF
              [realms]
              $REALM = {
              acl_file = /etc/krb5kdc/kadm5.acl
              max_renewable_life = 7d 0h 0m 0s
              supported_enctypes = $SUPPORTED_ENCRYPTION_TYPES
              default_principal_flags = +preauth
              }
              EOF
              
              echo "------ create /etc/krb5kdc/kadm5.acl";
              tee /etc/krb5kdc/kadm5.acl <<EOF
              $KADMIN_PRINCIPAL_FULL *
              noPermissions@$REALM X
              EOF
              
              echo "------- end of /kadm5.acl"
              ls -al /etc/krb5kdc/kadm5.acl;
              
              echo "------- Create newRealm";
              krb5_newrealm <<EOF
              $MASTER_PASSWORD
              $MASTER_PASSWORD
              EOF
              
              echo "------- Removing [$KADMIN_PRINCIPAL_FULL] for security";
              kadmin.local -q "delete_principal -force $KADMIN_PRINCIPAL_FULL" && \
              echo "Adding [$KADMIN_PRINCIPAL] principal" && \
              kadmin.local -q "addprinc -pw $KADMIN_PASSWORD $KADMIN_PRINCIPAL_FULL" && \
              kadmin.local -q "delete_principal -force noPermissions@$REALM" && \
              kadmin.local -q "addprinc -pw $KADMIN_PASSWORD noPermissions@$REALM" && \
              kadmin.local -q "addprinc -randkey FREEPDB1/0d1aa63b8227@$REALM" && \
              kadmin.local -q "ktadd -k /tmp/keytabs/keytab FREEPDB1/0d1aa63b8227@$REALM" && \
              kadmin.local -q "addprinc -pw camunda krbuser@$REALM" && \
              kadmin.local -q "addprinc -pw camunda krbtgt/EXAMPLE.COM@EXAMPLE.COM" && \
              rm -f /tmp/keytabs/camunda-identity-1.keytab && \
              kadmin.local -q "ktadd -k /tmp/keytabs/camunda-identity-1.keytab krbtgt/EXAMPLE.COM@EXAMPLE.COM" && \
              chmod -R a+rwx /tmp/keytabs/;
              ls -al /tmp/keytabs
              echo "-------- Done"              
              krb5kdc
              kadmind -nofork

          volumeMounts:
            - name: krb5-conf
              mountPath: /krb5-conf
            - name: krb5-data
              mountPath: /var/lib/krb5kdc
            - name: kdc-keytab
              mountPath: /tmp/keytabs
          env:
            - name: DEBIAN_FRONTEND
              value: noninteractive
            - name: MASTER_PASSWORD
              value: masterPassword
            - name: KADMIN_PASSWORD
              value: camunda
            - name: KADMIN_PRINCIPAL
              value: kadmin/admin
            - name: KADMIN_PRINCIPAL_FULL
              value: admin/admin@EXAMPLE.COM
            - name: REALM
              value: EXAMPLE.COM
            - name: SUPPORTED_ENCRYPTION_TYPES
              value: aes256-cts-hmac-sha1-96:normal

          ports:
            - containerPort: 88
              name: kerberos
            - containerPort: 464
              name: kpasswd
            - containerPort: 749
              name: kadmin

      volumes:
        - name: krb5-conf
          configMap:
            name: krb5-conf
        - name: krb5-data
          persistentVolumeClaim:
            claimName: krb5-data-pvc
        - name: kdc-keytab
          persistentVolumeClaim:
            claimName: krb5-keytab-pvc

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

* The `krb5.conf`is created as a config map, to be reuse

* the keytab us created by the pod at startup, and saved in a volum.

To remove it

```shell
kubernetes delete -f src/main/resources/kerberos/krb-kerberos.yaml -n camunda
```

## Oracle


Oracle pod need to access keytab created by the Kerberos pod, but there are no simple mechanism to have one pod creating the pvc, and other pod read it
A special PV must be created, but of course, the command does not work.

```shell
gcloud container clusters update CLUSTER_NAME \
--update-addons=GcpFilestoreCsiDriver \
--zone=ZONE
```

Then Oracle need to access
* the krb5.conf file (saved as a configmap, so accessible)
* the sqlnet.ora file (saved as a config map)

Identity needs to use a Jaas.conf, and it is prepared in this file too.


------------------ in progress

## Identity

identity needs to access
* the krb5.conf file (saved as a configmap, so accessible)
* the Jaas conf (saved as a config map)

The driver must be downloaded to and installed in a path, and the JVM parameter change to add this new class loader






