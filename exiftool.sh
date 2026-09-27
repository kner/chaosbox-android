exiftool -r -q  -if 'defined $Comment and $Comment ne ""'   -p '$Directory/$FileName $Comment' .
