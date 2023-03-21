#!/bin/bash

echo "Compiling code..."
make

echo "Running client with args $*"
java src.Client.IdClient "$@"
