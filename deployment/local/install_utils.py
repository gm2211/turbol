#!/usr/bin/env python3

from utils import *


def is_installed(command):
    result = None
    try:
        result = bash(command, print_error=False)
    except:
        pass
    if result is not None:
        print("'{}' already installed".format(command))
        return True
    print("'{}' not installed. Will install it..".format(command))
    return False


def pip_install(binary_name):
    bash("pip3 install --upgrade pip")
    bash("pip3 install {}".format(binary_name))


def download(binary_name, url_without_arch):
    arch = get_arch()
    url = url_without_arch.format(arch)
    bash("curl -Lo ./{}".format(binary_name), url, print_error=False)


def download_and_mv_to_usr_local_bin(binary_name, url_without_arch):
    download(binary_name, url_without_arch)
    bash("chmod +x ./{}".format(binary_name))
    bash("sudo mv ./{} /usr/local/bin/{}".format(binary_name, binary_name))


def download_and_install_ubuntu(binary_name, url_without_arch):
    download(binary_name, url_without_arch)
    bash("sudo dpkg -i {}".format(binary_name))
    bash("rm {}".format(binary_name))


def download_and_install_fedora(binary_name, url_without_arch):
    download(binary_name, url_without_arch)
    bash("sudo rpm -i {}".format(binary_name))
    bash("rm {}".format(binary_name))


def brew(binary_name):
    bash("brew update")
    return bash("brew install {}".format(binary_name))


def yum(binary_name):
    return bash("sudo yum install -y {}".format(binary_name))


def dnf(binary_name):
    return bash("sudo dnf install -y {}".format(binary_name))


def apt(binary_name):
    bash("sudo apt-get update")
    return bash("sudo apt-get install -y {}".format(binary_name))


def snap(binary_name, classic=False):
    if not is_installed("snap"):
        apt("snapd")
    return bash(
        "snap install {} {}"
        .format(
            binary_name,
            "--classic" if classic else ""
        )
        .strip()
    )


def maybe_install_docker(cur_os):
    if is_installed("docker"):
        return
    if cur_os == mac:
        print("Follow instructions at https://docs.docker.com/desktop/mac/install/")
    elif cur_os == ubuntu:
        print("Removing old docker..")
        bash("sudo apt-get remove docker docker-engine docker.io containerd runc")
        snap("docker")
    elif cur_os == fedora:
        print("Follow instructions at https://docs.docker.com/engine/install/fedora/")
        exit(-1)


def maybe_install_kind(cur_os):
    if is_installed("kind"):
        return
    if cur_os == mac:
        brew("kind")
    elif cur_os == ubuntu:
        download_and_mv_to_usr_local_bin("kind", "https://kind.sigs.k8s.io/dl/v0.11.1/kind-linux-{}")
    elif cur_os == fedora:
        dnf("kind")


def maybe_install_terraform(cur_os):
    if is_installed("terraform"):
        return
    if cur_os == mac:
        bash("brew update")
        bash("brew tap hashicorp/tap")
        brew("hashicorp/tap/terraform")
    elif cur_os == ubuntu:
        snap("terraform", classic=True)
    elif cur_os == fedora:
        dnf("dnf-plugins-core")
        bash("sudo dnf config-manager --add-repo https://rpm.releases.hashicorp.com/fedora/hashicorp.repo")
        dnf("terraform")


def maybe_install_kubectl(cur_os):
    if is_installed("kubectl"):
        return
    if cur_os == mac:
        pass
    elif cur_os == ubuntu:
        apt("apt-transport-https ca-certificates curl")
        bash(
            "sudo curl -fsSLo /usr/share/keyrings/kubernetes-archive-keyring.gpg https://packages.cloud.google.com/apt/doc/apt-key.gpg")
        pipe(
            bash(
                "echo",
                "deb [signed-by=/usr/share/keyrings/kubernetes-archive-keyring.gpg] https://apt.kubernetes.io/ kubernetes-xenial main",
                pre_pipe=True
            ),
            "sudo tee /etc/apt/sources.list.d/kubernetes.list"
        )
        apt("kubectl")
    elif cur_os == fedora:
        bash("""cat <<EOF | sudo tee /etc/yum.repos.d/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://packages.cloud.google.com/yum/repos/kubernetes-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF""")
        yum("kubectl")


def maybe_install_kubefwd(cur_os):
    if is_installed("kubefwd"):
        return
    if cur_os == mac:
        brew("txn2/tap/kubefwd")
    elif cur_os == ubuntu:
        download_and_install_ubuntu("kubefwd.deb",
                                    "https://github.com/txn2/kubefwd/releases/download/1.22.0/kubefwd_{}.deb")
    elif cur_os == fedora:
        download_and_install_fedora("kubefwd.rpm",
                                    "https://github.com/txn2/kubefwd/releases/download/1.22.0/kubefwd_{}.rpm")


def maybe_install_npm(cur_os):
    if is_installed("npm"):
        return
    if cur_os == mac:
        brew("npm")
    elif cur_os == ubuntu:
        apt("npm")
    elif cur_os == fedora:
        yum("npm")


def maybe_install_helm(cur_os):
    if is_installed("helm"):
        return
    if cur_os == mac:
        brew("helm")
    elif cur_os == ubuntu:
        snap("helm", classic=True)
    elif cur_os == fedora:
        yum("helm")


def maybe_install_yaml(cur_os):
    pip_install("pyyaml")
