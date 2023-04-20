#!/usr/bin/env python3

import subprocess
import os
from constants import *

def append_to_file(path, content):
  with open(path, 'a') as f:
    f.write(content)

def choose(prompt_message, num_by_option_name): 
  import sys
  options = "\n".join( "\t{}. {}".format(num_by_option_name[option_name], option_name) for option_name in num_by_option_name)
  maybe_option = input(
"""
{}:
{}
Choose one: """.format(prompt_message, options)
  )
  try:
    option = int(maybe_option)
  except:
    print("Option must be a number")
    sys.exit(-1)
  if not option in num_by_option_name.values(): 
    print("Option must be one of {}".format(list(num_by_option_name.values())))
    sys.exit(-1)
  return option

def choose_os(): 
  return choose("Available OSes", choice_num_by_os)

def pipe(cmd_1, cmd_2, print_error = True):
    pending_cmd_1 = (
      cmd_1 
        if isinstance(cmd_1, BashResult) 
        else bash(cmd_1, pre_pipe = True, print_error = print_error)
    )

    return bash(
      cmd_2, 
      stdin = pending_cmd_1.ps.stdout,
      print_error = print_error
    )

def bash(
  command, 
  args = "", 
  pre_pipe = False, # only to be set to true when used in combo with 'pipe(..)'
  print_error = True, 
  stdin = None
):
    rendered_args = [args] if args else []
    rendered_command = command.split() + rendered_args
    env = os.environ.copy()

    process = subprocess.Popen(
      rendered_command, 
      stdin=stdin,
      stderr=subprocess.PIPE, 
      stdout=subprocess.PIPE,
      env=env
    )

    if pre_pipe:
      return BashResult(process, None, None)

    output, error = process.communicate()
    output, error = (output.decode(), error.decode())
    if "not found" in output:
      return None
    if error and print_error:
      print("Error while executing command '{}' => {}".format(rendered_command, error)) 
    return BashResult(process, output, error)

def raw_bash(command):
    env = os.environ.copy()
    process = subprocess.Popen(
      command, 
      shell = True,
      env=env
    )
    process.communicate()
    return process

def get_arch():
  result = bash("dpkg --print-architecture", print_error = False)
  if not result.err:
    return result.out.strip()
  raw_arch = bash("uname -p").out.strip()
  if (raw_arch == "aarch64"):
    return "arm64"
  return raw_arch

def get_ubuntu_release_name():
  return bash("lsb_release -c | yq eval .Codename -")

class BashResult:
  def __init__(self, ps, out, err):
    self.ps = ps
    self.out = out
    self.err = err
