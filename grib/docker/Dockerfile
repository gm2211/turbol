# ======================================================================
# Dockerfile to compile wgrib2 based on Alpine linux
#
#           Homepage: http://www.cpc.ncep.noaa.gov/products/wesley/wgrib2/
# Available versions: ftp://ftp.cpc.ncep.noaa.gov/wd51we/wgrib2/
# ======================================================================

FROM area51/alpine-dev AS base
ARG version=3.1.2
ARG eccodes_archive_name="eccodes-2.28.0-Source"

ENV FILE_TYPE ""
ENV LINK_FILE_TO_DOWNLOAD ""
ENV GRIB_PARAMS ""
ENV ROUTING_KEY_PARSE_PREFIX ""
ENV GRIB_POSITION ""
ENV OTHER ""
ENV DEBUG true
ENV PYTHONUNBUFFERED=1


ENV CC=gcc
ENV FC=gfortran

RUN apk add --no-cache \
      zlib-dev

# Install python
RUN apk add --update --no-cache python3 && ln -sf python3 /usr/bin/python
RUN python3 -m ensurepip
RUN pip3 install --no-cache --upgrade pip setuptools

# Install wgrib2
RUN wget -q -O /tmp/wgrib2.tgz \
      ftp://ftp.cpc.ncep.noaa.gov/wd51we/wgrib2/wgrib2.tgz.v${version}

RUN mkdir -p /opt && \
    cd /opt/ && \
    tar -xf /tmp/wgrib2.tgz

RUN cd /opt/grib2 && \
    make

# Install eccodes
RUN wget -q -O /tmp/eccodes.tgz https://confluence.ecmwf.int/download/attachments/45757960/${eccodes_archive_name}.tar.gz

RUN cd /opt && \
    tar -xvf /tmp/eccodes.tgz && \
    rm /tmp/eccodes.tgz && \
    mv ${eccodes_archive_name} eccodes && \
    cd eccodes && \
    mkdir build && \
    cd build && \
    cmake ../ -DCMAKE_INSTALL_PREFIX=/opt/eccodes && \
    make && \
    ctest && \
    make install && \
    pip3 install --install-option="--prefix=/opt/eccodes" eccodes

RUN pip3 install cfgrib

RUN python -m cfgrib selfcheck

# ======================================================================
# The final image with alpine & just wgrib2 installed
FROM alpine

ENV PYTHONUNBUFFERED=1

RUN apk add --no-cache \
      ca-certificates \
      curl \
      libgfortran \
      libgomp

RUN apk add --update --no-cache python3 && ln -sf python3 /usr/bin/python
RUN python3 -m ensurepip
RUN pip3 install --no-cache --upgrade pip setuptools

COPY --from=base /opt/grib2/wgrib2/wgrib2 /usr/local/bin/wgrib2
COPY --from=base /opt/eccodes /opt/eccodes
COPY --from=base /opt/cfgrib /opt/cfgrib

RUN python -m cfgrib selfcheck

WORKDIR /opt/
