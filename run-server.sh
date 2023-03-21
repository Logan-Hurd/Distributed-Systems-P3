#!/bin/bash

DEFAULT_PORT_NUM=5180

# PORT_NUM=0
# FIRST_SERVER=$1
# if [ "$#" -lt 1 ]; then
#   echo "Port number not specified, defaulting to $DEFAULT_PORT_NUM"
#   PORT_NUM=5180
# else
#   PORT_NUM=$1
#   FIRST_SERVER=""
# fi
PORT_NUM=$DEFAULT_PORT_NUM

echo "Compiling code..."
make

echo "Starting server on port $PORT_NUM"
java src.Server.IdServer --numport $PORT_NUM --verbose $@
