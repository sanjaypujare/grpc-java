# Istio sidecar proxy injector installation for GKE

To install Istio sidecar proxy injector on GKE to be used with Traffic Director
two steps are necessary:

1. Create the secret for the sidecar injector:
   * key.pem --- the private key for the sidecar injector workload.
   * cert.pem --- the certificate of the sidecar injector workload.
   * ca-cert.pem --- the certificate of the signing CA.

```
kubectl apply -f specs/00-namespaces.yaml

kubectl create secret generic istio-sidecar-injector -n istio-control \
  --from-file=key.pem \
  --from-file=cert.pem \
  --from-file=ca-cert.pem
```

2.  Edit `specs/01-configmap.yaml` file:
    * replace `my-project-here` with your project number
    * replace `your-network-here` with the network name

3.  Deploy Istio sidecar injector by running:

    ```
    kubectl apply -f specs/
    ```

4.  Enable sidecar injection for the namespace by running following command,
    replacing <ns> with the name of the namespace:

    ```
    kubectl label namespace <ns> istio-injection=enabled
    ```

The following annotations are supported:

*   sidecar.istio.io/inject
    *   This is a Pod annotation. It specifies whether or not an Envoy sidecar
        should be automatically injected into the workload.
*   cloud.google.com/forwardPodLabels
    *   When this Pod annotation is set to "true", all Pod labels will be
        appended to Envoy metadata, annotation "cloud.google.com/proxyMetadata"
        will be ignored. Defaults to "false".
*   cloud.google.com/proxyMetadata
    *   This is a Pod annotation. The key/value pairs in this JSON map will be
        appended to Envoy metadata. This annotation only takes effect when
        annotation "cloud.google.com/forwardPodLabels" is not set to "true".
*   cloud.google.com/includeOutboundCIDRs
    *   This Pod annotation is a comma separated list of outbound IP ranges in
        CIDR form that will be redirected to the Envoy sidecar. The wildcard
        character "*" can be used to redirect all outbound traffic. An empty
        list will disable all outbound. Defaults to '*'.
*   cloud.google.com/excludeOutboundCIDRs
    *   This Pod annotation is a comma separated list of outbound IP ranges in
        CIDR form that will be excluded from redirection to the Envoy sidecar.
        This flag is only applicable when all outbound traffic (i.e. "*") is
        being redirected to the Envoy sidecar. Note that excluding
        "169.254.169.254/32" is required to ensure Pods can communicate with the
        metadata server. If you need to exclude some IP ranges from redirection,
        be sure to include the "169.254.169.254/32" CIDR to your list. Defaults
        to "169.254.169.254/32"
*   cloud.google.com/includeInboundPorts
    *   This Pod annotation is a comma separated list of inbound ports for which
        traffic is to be redirected to the Envoy sidecar. The wildcard character
        "*" can be used to configure redirection for all ports. An empty list
        will disable all inbound redirection. Defaults to an empty string (i.e.
        "").
*   cloud.google.com/excludeInboundPorts
    *   This Pod annotation is a comma separated list of inbound ports to be
        excluded from redirection to the Envoy sidecar. Only applies when all
        inbound traffic (i.e. "*") is being redirected. Defaults to be empty.
*   cloud.google.com/excludeOutboundPorts
    *   This Pod annotation is a comma separated list of outbound ports to be
        excluded from redirection to the Envoy sidecar. Defaults to an empty
        string (i.e. "").
*   cloud.google.com/enableManagedCerts
    *   When set to "true", this Pod annotation will insert and mount GKE
        managed workload certs (signed by CA Service) on the sidecar container.
        Defaults to "false".
