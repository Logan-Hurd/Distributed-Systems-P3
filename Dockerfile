FROM ubuntu:20.04

RUN apt update

RUN DEBIAN_FRONTEND=noninteractive apt install -y openjdk-11-jdk-headless make

WORKDIR cs455-p3

COPY . .

RUN make
