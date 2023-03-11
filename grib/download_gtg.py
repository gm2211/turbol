#!/usr/bin/env python3
import concurrent.futures
import math
import os
from datetime import datetime, timedelta

import requests
from bs4 import BeautifulSoup


def count_digits(num):
    if num < 10:
        return 1
    if num == 10:
        return 2
    return math.ceil(math.log(num) / math.log(10))


def pad_to_4_digits(num):
    num_digits = count_digits(num)
    if num_digits >= 4:
        return num
    num_padding = 4 - num_digits
    return "0" * num_padding + str(num)


def ask_yes_or_no(message):
    question = "{} [y/N]".format(message)
    answer = input(question)
    return answer.lower().strip() == 'y'


def print_progress(time_when_started, total_files, num_completed_files):
    elapsed_time: timedelta = datetime.now() - time_when_started
    time_per_file: timedelta = elapsed_time / num_completed_files if num_completed_files > 0 else 0
    remaining_files = total_files - num_completed_files
    remaining_time: timedelta = time_per_file * remaining_files
    print(
        f"Downloaded {num_completed_files}/{total_files} files in {elapsed_time}, "
        f"Remaining time: {remaining_time}, "
        f"Time per file: {time_per_file}, ")


def list_files(dir_url) -> list[str]:
    directory_url = "{}/".format(dir_url)
    print("Getting directory listing from: {}".format(directory_url))
    response = requests.get(directory_url)

    soup = BeautifulSoup(response.content, 'html.parser')
    print("Parsing directory listing...")
    file_tags = soup.find_all('a', href=True)

    result = []

    for tag in file_tags:
        href = tag['href']
        if href.endswith('.bin'):
            result.append(href)
    return result


def download_file_and_write_to_disk(base_url, timeout, filename):
    url = "{}/{}".format(base_url, filename)
    response = requests.get(url, timeout=timeout)
    if response.status_code != 200:
        print("Error ({}) while getting file: {}".format(response.status_code, filename))
    else:
        with open("./data/{}".format(filename), "wb") as f:
            f.write(response.content)


base_url = "https://tgftp.nws.noaa.gov/SL.us008001/DC.avspt/DS.gtggb/PT.grid_DF.gr2"
files = list_files(base_url)

print(f"Found {len(files)} files to download:")
for filename in files:
    print(filename)
if not ask_yes_or_no("Continue?"):
    exit()

download_timeout = int(input("What is the download timeout in seconds? (Default: 2 minutes) ") or "120")
max_concurrency = int(input("What is the maximum number of files to download at once? (Default: 10) ") or "10")

start_time: datetime = datetime.now()
completed_files = 0

if not os.path.exists('data'):
    os.makedirs('data')

with concurrent.futures.ThreadPoolExecutor(max_workers=max_concurrency) as executor:
    futures = []
    for filename in files:
        future = executor.submit(download_file_and_write_to_disk,
                                 base_url=base_url,
                                 timeout=download_timeout,
                                 filename=filename)
        futures.append(future)

    for future in concurrent.futures.as_completed(futures):
        completed_files += 1
        print_progress(start_time, len(files), completed_files)
