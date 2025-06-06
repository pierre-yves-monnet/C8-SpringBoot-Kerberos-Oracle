# C8-SpringBoot-Kerberos-Oracle
C8-SpringBoot-Kerberos-Oracle


# Create C8

kubectl create namespace camunda


# Oracle

Check the  [src/main/resources/oracle.yaml](src/main/resources/oracle.yaml) file

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


# Identity

## Download the driver

The first step consist of download the jdbc drivers. The driver is not publicetly accessible, so it must be downloaded from somewhere else.
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

