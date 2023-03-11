#!/usr/bin/env python3
import requests
import math

def count_digits(num):
    if num < 10:
        return 1
    if num == 10:
        return 2
    return math.ceil(math.log(num)/math.log(10))

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


base_url = "https://tgftp.nws.noaa.gov/SL.us008001/DC.avspt/DS.gtggb/PT.grid_DF.gr2"
done = False
interactive  = ask_yes_or_no("Interactive?")
counter = int(input("Start from: "))

while not done:
    filename = "sn.{}.bin".format(pad_to_4_digits(counter))
    url = "{}/{}".format(base_url, filename)
    data = requests.get(url)

    if data.status_code != 200:
        print("Error ({}) while getting file: {}".format(data.status_code, filename))
        done = True
    else:
        with open("./{}".format(filename), "wb") as f:
            print(data.text[:10])
            f.write(data.content)
        print("Written '{}' to disk".format(filename))
        counter += 1
        done = interactive and not ask_yes_or_no("Continue?")
