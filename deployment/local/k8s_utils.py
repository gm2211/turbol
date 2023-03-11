#!/usr/bin/env python3
from utils import *

get_contexts_command = "kubectl config get-contexts"


def set_active_cluster(cluster_name):
  bash("kubectl config use-context {}".format(cluster_name)).out

def select_k8s_cluster():
  contexts_to_display = bash(get_contexts_command).out

  print(contexts_to_display)

  context_names = [
    context_name 
    for context_name 
    in bash("{} -oname".format(get_contexts_command)).out.split("\n") 
    if context_name
  ]

  context_names_by_index = dict(enumerate(context_names))

  print("Choose one of the following:\n")

  for idx in context_names_by_index:
    print("\t{}. {}".format(idx, context_names_by_index[idx]))

  chosen_idx = input("Enter your choice: ")

  try:
    chosen_idx_int = int(chosen_idx) 
    assert chosen_idx_int in context_names_by_index
    chosen_cluster = context_names_by_index[chosen_idx_int]
    result = set_active_cluster(chosen_cluster)
    print("Result of setting '{}' as current cluster: {}".format(chosen_cluster, result))
    return context_names_by_index[chosen_idx_int].strip()
  except:
    print("Invalid choice. No-op")

if __name__ == "__main__":
  select_k8s_cluster()
