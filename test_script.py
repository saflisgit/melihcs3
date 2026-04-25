import urllib.request
import re

req = urllib.request.Request(
    'https://filmmakinesi.to', 
    headers={'User-Agent': 'Mozilla/5.0'}
)
html = urllib.request.urlopen(req).read().decode('utf-8')
links = re.findall(r'href=[\'\"](https://filmmakinesi\.to/[^\'\"]+-izle\.html)[\'\"]', html)
print(list(set(links))[:3])

if links:
    movie_url = links[0]
    req2 = urllib.request.Request(movie_url, headers={'User-Agent': 'Mozilla/5.0'})
    movie_html = urllib.request.urlopen(req2).read().decode('utf-8')
    print('URL:', movie_url)
    print('IFRAMES:', re.findall(r'<iframe.*?src=[\'\"](.*?)[\'\"]', movie_html))
    print('TITLE:', re.findall(r'<title>(.*?)</title>', movie_html))
    print('POSTER:', re.findall(r'property=\"og:image\".*?content=[\'\"](.*?)[\'\"]', movie_html))
    print('DESC:', re.findall(r'property=\"og:description\".*?content=[\'\"](.*?)[\'\"]', movie_html))
    
    # Try to find alternate video sources like data-src or script vars
    print('SOURCES (data-src):', re.findall(r'data-src=[\'\"](.*?\.m3u8.*?)[\'\"]', movie_html))
    print('MP4:', re.findall(r'[\'\"](.*?\.mp4.*?)[\'\"]', movie_html))
    print('EMBEDS:', re.findall(r'data-src=[\'\"](https://.*?/e/.*?)[\'\"]', movie_html))
    print('PLAYERS:', re.findall(r'data-src=[\'\"](.*?)[\'\"]', movie_html))

    print('DIVS WITH DATA-SRC:', re.findall(r'<div.*?data-src=[\'\"](.*?)[\'\"]', movie_html))
    print('IFRAMES WITH DATA-SRC:', re.findall(r'<iframe.*?data-src=[\'\"](.*?)[\'\"]', movie_html))
