/**
 * Hand the browser a file to save. An object URL and a click on a temporary
 * link, revoked straight after, so nothing lingers in memory or the page.
 */
export function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
