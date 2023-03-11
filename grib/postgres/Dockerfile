# ======================================================================
# Dockerfile to compile wgrib2 based on Alpine linux
#
#           Homepage: http://www.cpc.ncep.noaa.gov/products/wesley/wgrib2/
# Available versions: ftp://ftp.cpc.ncep.noaa.gov/wd51we/wgrib2/
# ======================================================================

FROM debian

RUN apt-get update
RUN apt-get install -y postgis
RUN apt-get install -y gdal-bin