#!/usr/bin/env python3

import k8s_utils as k8s
from install_utils import *


def install_deps():
    cur_os = choose_os()

    maybe_install_docker(cur_os)
    maybe_install_kind(cur_os)
    maybe_install_terraform(cur_os)
    maybe_install_kubectl(cur_os)
    maybe_install_kubefwd(cur_os)
    maybe_install_npm(cur_os)
    maybe_install_helm(cur_os)
    maybe_install_yaml(cur_os)


def create_kind_cluster(home_dir, delete_existing_cluster):
    kind_cluster_name = "local-dev"

    if delete_existing_cluster:
        print("Deleting existing kind cluster '{}'...".format(kind_cluster_name))
        raw_bash("sudo kind delete cluster --name {}".format(kind_cluster_name))

    print("Creating kind cluster '{}'...".format(kind_cluster_name))

    result = raw_bash("sudo kind create cluster --name {}".format(kind_cluster_name))

    if not result.stderr:
        k8s_cluster_name = "kind-{}".format(kind_cluster_name)
        print("Successfully created kind cluster '{}' with k8s name '{}'".format(kind_cluster_name, k8s_cluster_name))

        k8s_config = bash("sudo kind get kubeconfig --name {}".format(kind_cluster_name)).out
        k8s_config_dir = "{}/.kube".format(home_dir)
        k8s_config_path = "{}/config".format(k8s_config_dir)

        bash("mkdir -p {}".format(k8s_config_dir))
        bash("touch {}".format(k8s_config_path))
        append_to_file(k8s_config_path, k8s_config)
        return k8s_cluster_name

    if "already exist" in result.stderr:
        print("Kind cluster '{}' already exists".format(kind_cluster_name))
        return None

    print("Error while creating kind cluster '{}' => {}".format(kind_cluster_name, result.err))
    return None


def make_k8s_services_available_on_localhost():
    result = bash("sudo killall kubefwd")

    if not result.err:
        print("Waiting 5 seconds to give time to kubefwd to actually die..")
        bash("sleep 5")

    raw_bash('sudo KUBECONFIG="$HOME/.kube/config" kubefwd services -n default&')


def update_helm_repos():
    raw_bash("helm repo update")


def terraform_apply(cluster_name, script_dir, target=None):
    target_str = "--target={}".format(target) if target else ""
    raw_bash('terraform apply {} --auto-approve -var="k8s_cluster_name={}" -var-file="{}/../secrets.auto.tfvars"'.format(
        target_str, cluster_name, script_dir))


def terraform_init():
    raw_bash("terraform init")


def terraform_install_infra(cluster_name, script_dir):
    terraform_apply(cluster_name, script_dir, "module.local-infra")


def terraform_start_all(cluster_name, script_dir):
    terraform_apply(cluster_name, script_dir)


def start_all_services(cluster_name, old_cluster_was_deleted, script_dir):
    if old_cluster_was_deleted:
        print("Removing old terraform state because the old cluster was deleted")
        bash("rm {}/*.tfstate*".format(script_dir.strip("/")))

    terraform_init()
    terraform_install_infra(cluster_name, script_dir)

    make_k8s_services_available_on_localhost()  # make all services available on localhost
    terraform_start_all(cluster_name, script_dir)


def move_config(conf_dir):
    raw_bash("mv runtime.yml {}".format(conf_dir))
    raw_bash("mv install.yml {}".format(conf_dir))
    raw_bash("mv secrets.yml {}".format(conf_dir))


def get_http_port_from_config(conf_dir):
    import yaml

    with open("{}/install.yml".format(conf_dir), "r") as conf:
        return yaml.safe_load(conf)["server"]["port"]


def proxy_app_to_localhost():
    http_port = get_http_port_from_config(conf_dir)
    bash("sudo npm install -g local-ssl-proxy")
    raw_bash("nohup local-ssl-proxy --source 8051 --target {}&".format(http_port))


# MAIN
if __name__ == "__main__":
    import os, sys

    home_dir = os.path.expanduser('~')
    script_dir = os.path.dirname(os.path.realpath(__file__))
    conf_dir = "{}/../../backend/var/conf".format(script_dir)

    install_deps()

    delete_existing_cluster = input("Do you want to delete the existing cluster? [y/n]").lower() == 'y'
    cluster_name = create_kind_cluster(home_dir, delete_existing_cluster)

    if not cluster_name:
        print("Error when creating or updating Kind cluster. Exiting..")
        sys.exit(-1)

    k8s.set_active_cluster(cluster_name)

    update_helm_repos()

    start_all_services(cluster_name, delete_existing_cluster, script_dir)

    move_config(conf_dir)

    proxy_app_to_localhost()
